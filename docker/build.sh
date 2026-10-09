#!/usr/bin/env bash
# 构建镜像并推送到镜像仓库（默认推送到 Docker Hub 私有仓库，前后端同源，单个镜像单个端口）
# 用法：
#   docker login -u <DockerHub账号>        # 首次，密码填 Access Token
#   REGISTRY=<DockerHub账号> ./docker/build.sh
#   REGISTRY=<DockerHub账号> TAG=1.0.0 ./docker/build.sh
#   REGISTRY=<DockerHub账号> ./docker/build.sh --no-push    # 只构建，不推送
# 可覆盖的参数：REGISTRY / IMAGE / TAG / BASE_IMAGE
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# REGISTRY 是命名空间或自建仓库前缀：Docker Hub 填账号名（如 mycrop），自建仓库填完整前缀（如 harbor.corp.local/bizlens）
REGISTRY="${REGISTRY:-}"
IMAGE="${IMAGE:-bizlens-ai}"
TAG="${TAG:-latest}"
# 公开可用的 JDK8 运行环境（Ubuntu 版，含 apt，镜像内会按需补装 git）
BASE_IMAGE="${BASE_IMAGE:-eclipse-temurin:8-jre}"

if [ -n "$REGISTRY" ]; then
    FULL_IMAGE="$REGISTRY/$IMAGE:$TAG"
else
    FULL_IMAGE="$IMAGE:$TAG"
fi

PUSH=1
if [ "${1:-}" = "--no-push" ]; then
    PUSH=0
fi

# 推送前校验命名空间：Docker Hub 不接受没有命名空间（账号/组织）的镜像名
if [ "$PUSH" = "1" ]; then
    case "$FULL_IMAGE" in
        */*) : ;;
        *)
            echo "错误：镜像名 $FULL_IMAGE 没有命名空间，无法推送。" >&2
            echo "请用你的 Docker Hub 账号名重跑，例如： REGISTRY=mydockerid ./docker/build.sh" >&2
            echo "（也可写进 docker/.env：REGISTRY=mydockerid）" >&2
            exit 1
            ;;
    esac
fi

# 1. 校验 jar 存在
JAR="$(ls "$ROOT"/svc/target/bizlens-ai-service-*.jar 2>/dev/null | grep -v '\.original$' | head -1 || true)"
if [ -z "$JAR" ]; then
    echo "错误：未找到 svc/target/bizlens-ai-service-*.jar" >&2
    echo "请先在本地执行 bash docker/package.sh 打包，再上传本仓库到本机。" >&2
    exit 1
fi
echo "==> 使用 jar：$JAR"

# 2. 校验 jar 内含前端产物与 prod 配置，避免只部署了后端接口或配置缺失
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

# 3. 构建镜像（上下文为 svc/，只复制 jar，不在镜像内编译）
echo "==> docker build -t $FULL_IMAGE"
docker build \
    -f "$ROOT/svc/Dockerfile" \
    --build-arg "BASE_IMAGE=$BASE_IMAGE" \
    -t "$FULL_IMAGE" \
    "$ROOT/svc"

# 4. 推送
if [ "$PUSH" = "1" ]; then
    echo "==> docker push $FULL_IMAGE"
    if ! docker push "$FULL_IMAGE"; then
        echo "推送失败。若提示 unauthorized/denied，请先登录（密码用 Access Token，不是账号密码）：" >&2
        echo "  docker login -u <DockerHub账号>" >&2
        echo "若提示 repository does not exist，请先在 Docker Hub 网页端创建该私有仓库。" >&2
        exit 1
    fi
    echo "完成：$FULL_IMAGE"
else
    echo "完成（未推送）：$FULL_IMAGE"
fi
echo
echo "接着在目标机器上 docker login 后执行 ./docker/run.sh 启动容器。"
