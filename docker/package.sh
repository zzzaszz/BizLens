#!/usr/bin/env bash
# 本地打包（Windows / Linux 均可，需 Node+pnpm、JDK 8、Maven）：
#   1) 前端生产构建，产物并入 jar 的 classpath:/static/
#   2) mvn package 出可部署 jar
#   3) 把 jar 与服务器需要的全部脚本打成 bizlens-deploy.tar.gz —— 一台服务器只需传这一个文件
#
# 用法：bash docker/package.sh
#
# 上传目标可用环境变量覆盖（默认是本项目当前环境）：
#   DEPLOY_USER=zzzoooo2 DEPLOY_HOST=zzzoooo2.top DEPLOY_PORT=19992 DEPLOY_DIR=/vol1/1000/app
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WEB_DIR="$ROOT/web"
SVC_DIR="$ROOT/svc"
STATIC_DIR="$SVC_DIR/src/main/resources/static"
MARKER="$STATIC_DIR/.bizlens-frontend-build"
BUNDLE="$ROOT/bizlens-deploy.tar.gz"

DEPLOY_USER="${DEPLOY_USER:-zzzoooo2}"
DEPLOY_HOST="${DEPLOY_HOST:-zzzoooo2.top}"
DEPLOY_PORT="${DEPLOY_PORT:-19992}"
DEPLOY_DIR="${DEPLOY_DIR:-/vol1/1000/app}"

# 防误删：static 目录若存在且不是本脚本生成的（缺少标记文件），直接中止
if [ -d "$STATIC_DIR" ] && [ -n "$(ls -A "$STATIC_DIR" 2>/dev/null)" ] && [ ! -f "$MARKER" ]; then
    echo "错误：$STATIC_DIR 存在且非本脚本生成，已中止以免误删。" >&2
    echo "确认无用后请先手工清空该目录，再重新执行。" >&2
    exit 1
fi

echo "==> 1/5 构建前端（web/）"
cd "$WEB_DIR"
pnpm install --frozen-lockfile
pnpm run build

echo "==> 2/5 复制前端产物到 svc/src/main/resources/static"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -r "$WEB_DIR/dist/." "$STATIC_DIR/"
echo "bizlens frontend build" > "$MARKER"

echo "==> 3/5 打包后端（mvn package，跳过测试）"
cd "$ROOT"
mvn -pl svc -am clean package -DskipTests

echo "==> 4/5 清理临时静态资源目录"
rm -rf "$STATIC_DIR"

JAR="$(ls "$SVC_DIR"/target/bizlens-ai-service-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "错误：打包结束但未找到 svc/target/bizlens-ai-service-*.jar" >&2
    exit 1
fi
JAR_REL="svc/target/$(basename "$JAR")"

echo "==> 5/5 生成部署包 $BUNDLE"
# 只带服务器构建与运行需要的文件；docker/.env 是各环境自己的配置，不进包
rm -f "$BUNDLE"
cd "$ROOT"
tar -czf "$BUNDLE" \
    --exclude='docker/.env' \
    "$JAR_REL" \
    svc/Dockerfile \
    svc/.dockerignore \
    svc/entrypoint.sh \
    docker

# 校验内容齐全：缺一个都只会在服务器上构建时才暴露，这里先拦下来
REQUIRED="
svc/Dockerfile
svc/.dockerignore
svc/entrypoint.sh
$JAR_REL
docker/build.sh
docker/push.sh
docker/release.sh
docker/run.sh
docker/docker-compose.yml
docker/.env.example
"
CONTENT="$(tar -tzf "$BUNDLE")"
MISSING=""
for f in $REQUIRED; do
    echo "$CONTENT" | grep -qx "$f" || MISSING="$MISSING $f"
done
if [ -n "$MISSING" ]; then
    echo "错误：部署包缺少文件:$MISSING" >&2
    exit 1
fi

echo
echo "jar     ：$(ls -lh "$JAR" | awk '{print $5}')  $JAR_REL"
echo "部署包  ：$(ls -lh "$BUNDLE" | awk '{print $5}')  $BUNDLE"
echo "包内条目：$(echo "$CONTENT" | wc -l) 个"
echo
echo "下一步三步走（① 在本机，②③ 在服务器）"
echo
echo "① 本机：上传部署包"
echo "   scp -O -v -P $DEPLOY_PORT $(basename "$BUNDLE") $DEPLOY_USER@$DEPLOY_HOST:$DEPLOY_DIR/"
echo
echo "② 服务器：解包并确认到位（不能跳——只传压缩包，脚本不会自己出现）"
echo "   ssh -p $DEPLOY_PORT $DEPLOY_USER@$DEPLOY_HOST 'cd $DEPLOY_DIR && tar -xzf $(basename "$BUNDLE") && chmod +x docker/*.sh && ls -l docker/release.sh docker/docker-compose.yml svc/target/*.jar'"
echo
echo "③ 服务器：改配置后构建启动（bash 调用，不依赖脚本执行位）"
echo "   cd $DEPLOY_DIR && cp docker/.env.example docker/.env && vi docker/.env"
echo "   bash docker/release.sh <DockerHub账号> --skip-package"
echo "   bash docker/run.sh"
