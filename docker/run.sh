#!/usr/bin/env bash
# 在服务器上用 docker compose 启动 BizLens AI（前后端同源）
# 配置全部写死在 docker/docker-compose.yml 里，本脚本不读 .env
#
# 用法：
#   bash docker/run.sh              # 拉取最新镜像 + 启动 + 等健康检查
#   bash docker/run.sh --recreate   # 强制重建容器
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMPOSE_DIR="$ROOT/docker"
# host 网络模式下服务直接监听宿主机端口
SERVICE_PORT=10610
PUBLIC_HOST="zzzoooo2.top"

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

RECREATE=""
if [ "${1:-}" = "--recreate" ]; then
    RECREATE="--force-recreate"
fi

cd "$COMPOSE_DIR"

# 2. 拉取镜像（配置与镜像标签都在 docker-compose.yml 里）
echo "==> 拉取镜像（zzzoooo2/bizlens-ai，标签见 docker-compose.yml，请稍候）"
if ! PULL_OUT="$("${COMPOSE[@]}" pull 2>&1)"; then
    echo "$PULL_OUT" >&2
    echo >&2
    case "$PULL_OUT" in
        *"error during connect"*|*"Cannot connect to the Docker daemon"*|*"docker daemon is not running"*)
            echo "错误：连不上 Docker daemon，请确认本机 Docker 已启动。" >&2
            ;;
        *"context deadline exceeded"*|*"i/o timeout"*|*"TLS handshake timeout"*|*"connection timed out"*)
            echo "错误：连不上镜像仓库（超时）。本机出网需要走代理时，要给 Docker daemon 配代理后重试：" >&2
            echo "  /etc/docker/daemon.json 里加 proxies.http-proxy / https-proxy（写法见说明文档常见问题）" >&2
            ;;
        *unauthorized*|*denied*|*"authentication required"*|*"no basic auth credentials"*)
            echo "拉取被拒：私有仓库需先登录（密码填 Access Token）：" >&2
            echo "  docker login -u zzzoooo2" >&2
            ;;
        *"repository does not exist"*|*"manifest unknown"*|*"not found"*)
            echo "镜像不存在：请确认 docker-compose.yml 里的 REGISTRY/标签，以及该版本是否已推送。" >&2
            ;;
        *)
            echo "拉取失败，处理办法见上方报错。" >&2
            ;;
    esac
    exit 1
fi

# 3. 启动
echo "==> 启动容器（host 网络，服务监听宿主机 ${SERVICE_PORT}）"
"${COMPOSE[@]}" up -d $RECREATE

# 4. 等就绪：优先看镜像内 HEALTHCHECK 的结果，老镜像没有健康检查时退回探针接口
echo "==> 等待服务就绪：http://127.0.0.1:${SERVICE_PORT}/api/system/health"
for _ in $(seq 1 45); do
    status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{end}}' bizlens-ai 2>/dev/null || true)"
    if [ "$status" = "healthy" ]; then
        echo "==> 服务已就绪，前端地址：http://${PUBLIC_HOST}:${SERVICE_PORT}/"
        exit 0
    fi
    if [ -z "$status" ] && curl -fsS "http://127.0.0.1:${SERVICE_PORT}/api/system/health" >/dev/null 2>&1; then
        echo "==> 服务已就绪（镜像内无健康检查，已用探针接口确认）"
        echo "    前端地址：http://${PUBLIC_HOST}:${SERVICE_PORT}/"
        exit 0
    fi
    sleep 2
done

echo "警告：90 秒内未就绪。查看日志排查：" >&2
echo "  cd docker && docker compose logs -f" >&2
echo "或 docker logs -f bizlens-ai" >&2
exit 1
