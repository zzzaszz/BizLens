#!/usr/bin/env bash
# 在服务器上通过 docker compose 启动 BizLens AI（前后端同源，只映射一个端口）
# 私有仓库需先登录：docker login -u <DockerHub账号>（密码用 Access Token）
#
# 用法：
#   cp docker/.env.example docker/.env && vi docker/.env
#   ./docker/run.sh                 # 拉取最新镜像 + 启动 + 等健康检查
#   ./docker/run.sh --recreate      # 强制重建容器
#
# 也可直接用 compose（在 docker/ 目录下）：
#   docker compose pull && docker compose up -d
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_DIR="$ROOT/docker"
ENV_FILE="${ENV_FILE:-$COMPOSE_DIR/.env}"

# 1. 确定 compose 命令：新版 Docker 是 "docker compose"，老服务器可能只有 v1 的 "docker-compose"
if docker compose version >/dev/null 2>&1; then
    COMPOSE=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE=(docker-compose)
    echo "注意：本机是 Docker Compose v1（docker-compose），若报 Unsupported config option，请升级到 compose v2。" >&2
else
    echo "错误：找不到 docker compose。" >&2
    echo "请安装 Docker Compose 插件（新版 Docker 自带），或升级 Docker。" >&2
    exit 1
fi

# 2. 环境变量文件
if [ ! -f "$ENV_FILE" ]; then
    echo "错误：未找到 $ENV_FILE" >&2
    echo "请先复制模板并填写：cp docker/.env.example docker/.env" >&2
    exit 1
fi

# 用临时副本读取 compose 变量，顺带去掉 Windows 编辑器可能写入的 CR
# （否则 JDBC/Redis 的值会带上 \r，连不上库）
echo "==> 读取环境变量：$ENV_FILE"
ENV_CLEAN="$(mktemp)"
trap 'rm -f "$ENV_CLEAN"' EXIT
tr -d '\r' < "$ENV_FILE" > "$ENV_CLEAN"

# 命令行给出的值优先于 .env（与 compose 自身的优先级一致，便于临时换标签/端口）
PRESET_REGISTRY="${REGISTRY:-}"
PRESET_IMAGE="${IMAGE:-}"
PRESET_TAG="${TAG:-}"
PRESET_HOST_PORT="${HOST_PORT:-}"
PRESET_PUBLIC_HOST="${PUBLIC_HOST:-}"

set -a
# shellcheck disable=SC1090
. "$ENV_CLEAN"
set +a

REGISTRY="${PRESET_REGISTRY:-$REGISTRY}"
IMAGE="${PRESET_IMAGE:-$IMAGE}"
TAG="${PRESET_TAG:-$TAG}"
HOST_PORT="${PRESET_HOST_PORT:-${HOST_PORT:-10610}}"
# 仅用于打印访问地址；服务自身的健康检查一律走 127.0.0.1，不依赖 DNS/回环
PUBLIC_HOST="${PRESET_PUBLIC_HOST:-${PUBLIC_HOST:-zzzoooo2.top}}"

for key in REGISTRY DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD REDIS_HOST REDIS_PORT; do
    if [ -z "${!key:-}" ]; then
        echo "错误：$ENV_FILE 内未设置 $key" >&2
        exit 1
    fi
done

HOST_PORT="${HOST_PORT:-10610}"
RECREATE=""
if [ "${1:-}" = "--recreate" ]; then
    RECREATE="--force-recreate"
fi

cd "$COMPOSE_DIR"

# 3. 拉取镜像（失败时按错误内容给出对应处理办法，避免一律提示去登录）
echo "==> 拉取镜像：$REGISTRY/bizlens-ai:${TAG:-latest}（请稍候）"
if ! PULL_OUT="$("${COMPOSE[@]}" --env-file "$ENV_CLEAN" pull 2>&1)"; then
    echo "$PULL_OUT" >&2
    echo >&2
    case "$PULL_OUT" in
        *"error during connect"*|*"Cannot connect to the Docker daemon"*|*"docker daemon is not running"*)
            echo "错误：连不上 Docker daemon，请确认本机 Docker 已启动。" >&2
            ;;
        *unauthorized*|*denied*|*"authentication required"*|*"no basic auth credentials"*)
            echo "拉取被拒：私有仓库需先登录（密码填 Access Token）：" >&2
            echo "  docker login -u <DockerHub账号>" >&2
            ;;
        *"repository does not exist"*|*"manifest unknown"*|*"not found"*)
            echo "镜像不存在：请确认 .env 里的 REGISTRY / IMAGE / TAG 是否正确，以及该版本是否已推送。" >&2
            ;;
        *)
            echo "拉取失败，处理办法见上方报错。" >&2
            ;;
    esac
    exit 1
fi

# 4. 启动
echo "==> 启动容器（宿主机 $HOST_PORT -> 容器 10610）"
"${COMPOSE[@]}" --env-file "$ENV_CLEAN" up -d $RECREATE

# 5. 等就绪：优先看镜像内 HEALTHCHECK 的结果，老镜像没有健康检查时退回探针接口
echo "==> 等待服务就绪：http://127.0.0.1:${HOST_PORT}/api/system/health"
for _ in $(seq 1 45); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' bizlens-ai 2>/dev/null || true)"
    if [ "$status" = "healthy" ]; then
        echo "==> 服务已就绪，前端地址：http://${PUBLIC_HOST}:${HOST_PORT}/"
        exit 0
    fi
    if [ -z "$status" ] && curl -fsS "http://127.0.0.1:${HOST_PORT}/api/system/health" >/dev/null 2>&1; then
        echo "==> 服务已就绪（镜像内无健康检查，已用探针接口确认）"
        echo "    前端地址：http://${PUBLIC_HOST}:${HOST_PORT}/"
        exit 0
    fi
    sleep 2
done

echo "警告：90 秒内未就绪。查看日志排查：" >&2
echo "  cd docker && docker compose logs -f" >&2
echo "或 docker logs -f bizlens-ai" >&2
exit 1
