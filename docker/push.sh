#!/usr/bin/env bash
# 把已构建好的镜像推送到镜像仓库（默认 Docker Hub 私有仓库）
# 推送前需先登录：docker login -u zzzoooo2（密码用 Access Token）
#
# 用法：
#   ./docker/push.sh zzzoooo2 [标签]
#   ./docker/push.sh zzzoooo2 1.0.0
#   ./docker/push.sh zzzoooo2 1.0.0 --latest      # 同时把 latest 也指向该版本
#   REGISTRY=zzzoooo2 TAG=1.0.0 ./docker/push.sh
set -euo pipefail

IMAGE="${IMAGE:-bizlens-ai}"

ACCOUNT=""
PUSH_TAG=""
ALSO_LATEST=0

for arg in "$@"; do
    case "$arg" in
        --latest) ALSO_LATEST=1 ;;
        --help|-h)
            # 打印文件开头的用法注释（到首个非注释行为止）
            awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "$0"
            exit 0
            ;;
        --*)
            echo "错误：未知参数 $arg" >&2
            exit 1
            ;;
        *)
            if [ -z "$ACCOUNT" ]; then
                ACCOUNT="$arg"
            elif [ -z "$PUSH_TAG" ]; then
                PUSH_TAG="$arg"
            else
                echo "错误：多余参数 $arg" >&2
                exit 1
            fi
            ;;
    esac
done

ACCOUNT="${ACCOUNT:-${REGISTRY:-}}"
PUSH_TAG="${PUSH_TAG:-${TAG:-latest}}"

# 1. 命名空间：Docker Hub 不接受没有命名空间（账号/组织）的镜像名
if [ -z "$ACCOUNT" ]; then
    echo "错误：未指定 Docker Hub 账号（镜像命名空间）。" >&2
    echo "用法：./docker/push.sh zzzoooo2 [标签]" >&2
    echo "例如：./docker/push.sh zzzoooo2 1.0.0" >&2
    exit 1
fi
FULL_IMAGE="$ACCOUNT/$IMAGE:$PUSH_TAG"

# 2. 确认 Docker daemon 可用（Windows 上多半是 Docker Desktop 没启动）
if ! docker info >/dev/null 2>&1; then
    echo "错误：连不上 Docker daemon。" >&2
    echo "Windows 上请先启动 Docker Desktop，等托盘图标显示为运行中再重试。" >&2
    exit 1
fi

# 3. 本地必须已有这个镜像，否则推送必然失败，先给出明确指引
if ! docker image inspect "$FULL_IMAGE" >/dev/null 2>&1; then
    echo "错误：本地没有镜像 $FULL_IMAGE" >&2
    echo "请先构建： REGISTRY=$ACCOUNT TAG=$PUSH_TAG ./docker/build.sh" >&2
    exit 1
fi

# 4. 推送
echo "==> docker push $FULL_IMAGE"
if ! docker push "$FULL_IMAGE"; then
    echo "推送失败。若提示 unauthorized/denied，请先登录（密码用 Access Token，不是账号密码）：" >&2
    echo "  docker login -u zzzoooo2" >&2
    echo "若提示 repository does not exist，请先在 Docker Hub 网页端创建该私有仓库。" >&2
    exit 1
fi

# 5. 可选：把 latest 一并指向该版本
if [ "$ALSO_LATEST" = "1" ] && [ "$PUSH_TAG" != "latest" ]; then
    LATEST_IMAGE="$ACCOUNT/$IMAGE:latest"
    echo "==> 同时更新 $LATEST_IMAGE"
    docker tag "$FULL_IMAGE" "$LATEST_IMAGE"
    docker push "$LATEST_IMAGE"
fi

echo
echo "完成：$FULL_IMAGE"
echo "服务器上执行： docker login -u $ACCOUNT && TAG=$PUSH_TAG ./docker/run.sh"
