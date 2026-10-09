#!/bin/sh
set -e  # 遇到错误立即退出

# 必填环境变量校验：缺失时直接退出，比启动到一半报数据库连接错误更容易定位
missing=""
for key in DATABASE_URL DATABASE_USERNAME DATABASE_PASSWORD REDIS_HOST REDIS_PORT; do
    if [ -z "$(printenv "$key")" ]; then
        missing="$missing $key"
    fi
done
if [ -n "$missing" ]; then
    echo "错误：缺少必需的环境变量:$missing" >&2
    echo "请用 docker run -e KEY=VALUE 传入，参考 docker/.env.example 与 docker/run.sh" >&2
    exit 1
fi

for key in AES_KEY JWT_SECRET; do
    if [ -z "$(printenv "$key")" ]; then
        echo "警告：未设置 $key，将退化为源码内置的开发密钥（Git 令牌加解密 / 登录态签发），生产环境请务必传入" >&2
    fi
done

# 打印将要连接的目标，便于排查；口令不打印
echo "启动 BizLens AI（prod 配置在 jar 内，取值来自环境变量）"
echo "  DATABASE_URL   = $(printenv DATABASE_URL)"
echo "  DATABASE_USER  = $(printenv DATABASE_USERNAME)"
echo "  REDIS          = $(printenv REDIS_HOST):$(printenv REDIS_PORT)"

# 启动服务：前端静态资源已并入 jar，前后端同源，只需一个端口
# PARAMS 用于追加参数，如 -e PARAMS="--server.port=8080"
# shellcheck disable=SC2086
exec java -Duser.timezone=GMT+8 -jar /home/inks/app.jar \
    --spring.profiles.active=prod \
    $PARAMS
