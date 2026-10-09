#!/usr/bin/env bash
# 在目标机器上启动 BizLens AI：从镜像仓库拉取后以单容器运行，只映射一个端口
# 私有仓库需先登录：docker login -u <DockerHub账号>
# 用法：
#   cp docker/.env.example docker/.env   # 首次：填写数据库/Redis 等环境变量
#   ./docker/run.sh
# 可覆盖的参数：REGISTRY / IMAGE / TAG / HOST_PORT / NAME
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT/docker/.env}"

if [ ! -f "$ENV_FILE" ]; then
    echo "错误：未找到 $ENV_FILE" >&2
    echo "请先复制模板并填写：cp docker/.env.example docker/.env" >&2
    exit 1
fi
echo "==> 读取环境变量：$ENV_FILE"
# 用临时副本读取，顺带去掉 Windows 编辑器可能写入的 CR（否则 JDBC/Redis 值会带上 \r 导致连接失败）
ENV_CLEAN="$(mktemp)"
trap 'rm -f "$ENV_CLEAN"' EXIT
tr -d '\r' < "$ENV_FILE" > "$ENV_CLEAN"
set -a
# shellcheck disable=SC1090
. "$ENV_CLEAN"
set +a

REGISTRY="${REGISTRY:-}"
IMAGE="${IMAGE:-bizlens-ai}"
TAG="${TAG:-latest}"
HOST_PORT="${HOST_PORT:-10610}"
NAME="${NAME:-bizlens-ai}"
CONTAINER_PORT=10610

for key in DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD REDIS_HOST REDIS_PORT; do
    if [ -z "${!key:-}" ]; then
        echo "错误：$ENV_FILE 内未设置 $key" >&2
        exit 1
    fi
done

if [ -n "$REGISTRY" ]; then
    FULL_IMAGE="$REGISTRY/$IMAGE:$TAG"
else
    FULL_IMAGE="$IMAGE:$TAG"
fi

# 私有仓库的镜像名必须带命名空间，否则拉不到
case "$FULL_IMAGE" in
    */*) : ;;
    *)
        echo "错误：镜像名 $FULL_IMAGE 没有命名空间。" >&2
        echo "请在 $ENV_FILE 里填写 REGISTRY=<你的 Docker Hub 账号>，或执行时传入 REGISTRY=xxx ./docker/run.sh" >&2
        exit 1
        ;;
esac

echo "==> 拉取镜像 $FULL_IMAGE"
if ! docker pull "$FULL_IMAGE"; then
    echo "拉取失败。私有仓库需先登录（密码用 Access Token）：" >&2
    echo "  docker login -u <DockerHub账号>" >&2
    echo "自建仓库请加地址： docker login <仓库地址>" >&2
    exit 1
fi

echo "==> 清理同名旧容器（如有）"
docker rm -f "$NAME" >/dev/null 2>&1 || true

echo "==> 启动容器（宿主机 $HOST_PORT -> 容器 $CONTAINER_PORT）"
docker run -d \
    --name "$NAME" \
    --restart unless-stopped \
    -p "${HOST_PORT}:${CONTAINER_PORT}" \
    --env-file "$ENV_CLEAN" \
    "$FULL_IMAGE"

echo
echo "==> 启动完成，等待健康检查：http://127.0.0.1:${HOST_PORT}/api/system/health"
for _ in $(seq 1 30); do
    if curl -fsS "http://127.0.0.1:${HOST_PORT}/api/system/health" >/dev/null 2>&1; then
        echo "==> 服务已就绪，前端地址：http://<本机IP>:${HOST_PORT}/"
        exit 0
    fi
    sleep 2
done

echo "警告：60 秒内未通过健康检查，请查看日志：docker logs -f $NAME" >&2
exit 1
