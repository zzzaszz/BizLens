#!/usr/bin/env bash
# 本地打包：前端生产构建 -> 并入后端 jar 的 classpath:/static/ -> mvn package
# 产出一个可部署的 jar（前后端都在里面），再交给 docker/build.sh 构建镜像。
# 用法：./docker/package.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WEB_DIR="$ROOT/web"
SVC_DIR="$ROOT/svc"
STATIC_DIR="$SVC_DIR/src/main/resources/static"
MARKER="$STATIC_DIR/.bizlens-frontend-build"

# 防误删：static 目录若存在且不是本脚本生成的（缺少标记文件），直接中止
if [ -d "$STATIC_DIR" ] && [ -n "$(ls -A "$STATIC_DIR" 2>/dev/null)" ] && [ ! -f "$MARKER" ]; then
    echo "错误：$STATIC_DIR 存在且非本脚本生成，已中止以免误删。" >&2
    echo "确认无用后请先手工清空该目录，再重新执行。" >&2
    exit 1
fi

echo "==> 1/4 构建前端（web/）"
cd "$WEB_DIR"
pnpm install --frozen-lockfile
pnpm run build

echo "==> 2/4 复制前端产物到 svc/src/main/resources/static"
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -r "$WEB_DIR/dist/." "$STATIC_DIR/"
echo "bizlens frontend build" > "$MARKER"

echo "==> 3/4 打包后端（mvn package，跳过测试）"
cd "$ROOT"
mvn -pl svc -am clean package -DskipTests

echo "==> 4/4 清理临时静态资源目录"
rm -rf "$STATIC_DIR"

JAR="$(ls "$SVC_DIR"/target/bizlens-ai-service-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "错误：打包结束但未找到 svc/target/bizlens-ai-service-*.jar" >&2
    exit 1
fi
echo "完成：$JAR"
ls -lh "$JAR"
echo
echo "下一步：把该 jar 放到 Linux 机器上，执行 ./docker/build.sh 构建并推送镜像。"
