#!/usr/bin/env bash
# 一条命令完成：打包 jar -> 构建镜像 -> 推送到 Docker Hub 私有仓库
# 需要本机装有 Docker；Windows 上即 Docker Desktop 处于运行状态。
# 本机没有 Docker 时不要用本脚本：把 jar 传到服务器，在服务器上执行
#   ./docker/release.sh <账号> --skip-package
#
# 用法：
#   ./docker/release.sh <DockerHub账号>              # 标签自动生成：时间戳 20261009-1431
#   ./docker/release.sh <账号> --git                 # 标签用 git 提交短哈希，可追溯到代码
#   ./docker/release.sh <账号> 1.0.0                 # 指定标签
#   ./docker/release.sh <账号> 1.0.0 --skip-package  # 不重新打包，用现有 jar
#   ./docker/release.sh <账号> 1.0.0 --no-push       # 只打包 + 构建，不推送
#
# 说明：Docker 自身不会生成版本号（不带 -t 只会得到一个无名镜像，无法推送），
#       所以标签由本脚本生成。自动生成时还会同时把 latest 指向该版本，方便服务器直接拉取。
# 也可用环境变量传：REGISTRY=<账号> TAG=1.0.0 ./docker/release.sh
# 逐步执行见 docker/package.sh、docker/build.sh、docker/push.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

ACCOUNT=""
RELEASE_TAG=""
SKIP_PACKAGE=0
DO_PUSH=1
USE_GIT_TAG=0

for arg in "$@"; do
    case "$arg" in
        --skip-package) SKIP_PACKAGE=1 ;;
        --no-push) DO_PUSH=0 ;;
        --git) USE_GIT_TAG=1 ;;
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
            elif [ -z "$RELEASE_TAG" ]; then
                RELEASE_TAG="$arg"
            else
                echo "错误：多余参数 $arg" >&2
                exit 1
            fi
            ;;
    esac
done

ACCOUNT="${ACCOUNT:-${REGISTRY:-}}"
RELEASE_TAG="${RELEASE_TAG:-${TAG:-}}"

if [ -z "$ACCOUNT" ]; then
    echo "错误：未指定 Docker Hub 账号（镜像命名空间）。" >&2
    echo "用法：./docker/release.sh <DockerHub账号> [标签]" >&2
    echo "例如：./docker/release.sh myid            # 标签自动生成" >&2
    echo "      ./docker/release.sh myid 1.0.0      # 指定标签" >&2
    exit 1
fi

# 标签：没给就自动生成（默认时间戳，--git 用提交短哈希）
AUTO_TAG=0
if [ -z "$RELEASE_TAG" ]; then
    AUTO_TAG=1
    if [ "$USE_GIT_TAG" = "1" ]; then
        RELEASE_TAG="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || true)"
        if [ -z "$RELEASE_TAG" ]; then
            echo "提示：本目录不是 git 仓库或取不到提交号，改用时间戳标签" >&2
        fi
    fi
    if [ -z "$RELEASE_TAG" ]; then
        RELEASE_TAG="$(date +%Y%m%d-%H%M)"
    fi
fi
if [ "$AUTO_TAG" = "1" ]; then
    echo "==> 版本标签：$RELEASE_TAG（自动生成，并会同时更新 latest）"
else
    echo "==> 版本标签：$RELEASE_TAG"
fi

# 先确认 Docker 可用，避免白跑一遍打包（Windows 上多半是 Docker Desktop 没启动）
if ! docker info >/dev/null 2>&1; then
    # 自动生成标签时提示里不带标签，让服务器按自己的当前时间生成
    if [ "$AUTO_TAG" = "1" ]; then
        HINT_TAG=""
    else
        HINT_TAG="$RELEASE_TAG "
    fi
    echo "错误：连不上 Docker daemon。" >&2
    echo "Windows 上请先启动 Docker Desktop；若本机没装 Docker，请在装了 Docker 的服务器上执行：" >&2
    echo "  ./docker/release.sh $ACCOUNT ${HINT_TAG}--skip-package" >&2
    exit 1
fi

if [ "$SKIP_PACKAGE" = "0" ]; then
    echo "==> 1/3 打包 jar（前端 + 后端）"
    bash "$ROOT/docker/package.sh"
else
    echo "==> 1/3 跳过打包（--skip-package）"
fi

echo "==> 2/3 构建镜像"
REGISTRY="$ACCOUNT" TAG="$RELEASE_TAG" bash "$ROOT/docker/build.sh"

if [ "$DO_PUSH" = "1" ]; then
    echo "==> 3/3 推送镜像"
    if [ "$AUTO_TAG" = "1" ]; then
        bash "$ROOT/docker/push.sh" "$ACCOUNT" "$RELEASE_TAG" --latest
    else
        bash "$ROOT/docker/push.sh" "$ACCOUNT" "$RELEASE_TAG"
    fi
else
    echo "==> 3/3 跳过推送（--no-push）"
    echo
    echo "完成（未推送）：$ACCOUNT/bizlens-ai:$RELEASE_TAG"
    echo "推送： ./docker/push.sh $ACCOUNT $RELEASE_TAG"
fi

echo
echo "服务器上执行： docker login -u $ACCOUNT && TAG=$RELEASE_TAG ./docker/run.sh"
echo "（自动生成的标签已同时推到 latest，服务器也可直接 ./docker/run.sh）"
