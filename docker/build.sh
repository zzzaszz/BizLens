#!/usr/bin/env bash
# 构建镜像（只构建，不推送）。推送用 docker/push.sh；一步到位用 docker/release.sh。
# 只需要 Docker，不需要 JDK/Maven —— 镜像内不编译，只复制已打好的 jar。
# 没有 Docker Desktop 的 Windows 机器：把 jar 传到装了 Docker 的服务器，在服务器上执行本脚本。
#
# 用法：
#   REGISTRY=<DockerHub账号> ./docker/build.sh
#   REGISTRY=<账号> TAG=1.0.0 ./docker/build.sh
#   REGISTRY=<账号> BASE_IMAGE=eclipse-temurin:8-jre ./docker/build.sh
# 可覆盖的参数：REGISTRY / IMAGE / TAG / BASE_IMAGE
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# REGISTRY 是命名空间或自建仓库前缀：Docker Hub 填账号名（如 myid），自建仓库填完整前缀（如 harbor.corp.local/bizlens）
REGISTRY="${REGISTRY:-}"
IMAGE="${IMAGE:-bizlens-ai}"
TAG="${TAG:-latest}"
# 公共可用的 JDK8 运行环境（Ubuntu 版，含 apt，镜像内会按需补装 git）
BASE_IMAGE="${BASE_IMAGE:-eclipse-temurin:8-jre}"

# 1. 构建时就把名字定成最终要推的名字，避免构建与推送两处名字对不上
if [ -z "$REGISTRY" ]; then
    echo "错误：未指定镜像命名空间 REGISTRY（Docker Hub 账号名）。" >&2
    echo "例如： REGISTRY=mydockerid ./docker/build.sh" >&2
    exit 1
fi
FULL_IMAGE="$REGISTRY/$IMAGE:$TAG"

# 2. 校验 jar 存在
JAR="$(ls "$ROOT"/svc/target/bizlens-ai-service-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "错误：未找到 svc/target/bizlens-ai-service-*.jar" >&2
    echo "请先执行 bash docker/package.sh 打包（或用 docker/release.sh 一步完成）。" >&2
    exit 1
fi
echo "==> 使用 jar：$JAR"

# 3. 校验 jar 内含前端产物与 prod 配置，避免只部署了后端接口或配置缺失
if command -v unzip >/dev/null 2>&1; then
    LIST="$(unzip -l "$JAR" | grep 'BOOT-INF/classes/' || true)"
    if ! echo "$LIST" | grep -q "BOOT-INF/classes/static/index.html"; then
        echo "错误：jar 内未找到前端产物（BOOT-INF/classes/static/index.html）" >&2
        echo "说明 jar 是旧包或未执行打包，请重新执行 bash docker/package.sh。" >&2
        exit 1
    fi
    if ! echo "$LIST" | grep -q "BOOT-INF/classes/application-prod.yml"; then
        echo "错误：jar 内未找到生产配置（BOOT-INF/classes/application-prod.yml）" >&2
        echo "请重新执行 bash docker/package.sh 打包。" >&2
        exit 1
    fi
    echo "==> 已确认 jar 内含前端产物与生产配置"
else
    echo "提示：本机无 unzip，跳过 jar 内含内容的校验"
fi

# 4. 确认 Docker daemon 可用
if ! docker info >/dev/null 2>&1; then
    echo "错误：连不上 Docker daemon。" >&2
    echo "Windows 上请先启动 Docker Desktop；若本机没装 Docker，把 jar 传到服务器后再执行本脚本。" >&2
    exit 1
fi

# 5. 构建镜像
# 切到 svc/ 后用相对路径传参，避免 Git Bash 把 /d/... 形式的路径转换成 Windows 路径
cd "$ROOT/svc"
echo "==> docker build -t $FULL_IMAGE"
docker build \
    -f Dockerfile \
    --build-arg "BASE_IMAGE=$BASE_IMAGE" \
    -t "$FULL_IMAGE" \
    .

echo
echo "构建完成：$FULL_IMAGE"
echo "下一步推送： ./docker/push.sh $REGISTRY $TAG"
