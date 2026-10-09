# BizLens AI 一体化镜像：Spring Boot 后端 + 已并入 jar 的 Vue3 前端静态资源
# 构建上下文为 svc/，镜像内不编译，只复制 docker/package.sh 产出的 jar
# 生产配置随 jar 发布（application-prod.yml），数据库/Redis 取值全部来自运行时的环境变量
# 构建与推送见 docker/build.sh
ARG BASE_IMAGE=eclipse-temurin:8-jre
FROM ${BASE_IMAGE}

# author
MAINTAINER inks

USER root

# 代码/知识库索引依赖 git 命令行（浅克隆仓库），健康检查依赖 curl，基础镜像自带则跳过安装
RUN set -e; \
    if command -v git >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then \
        echo "git 与 curl 已存在"; \
    elif command -v apt-get >/dev/null 2>&1; then \
        apt-get update \
        && apt-get install -y --no-install-recommends git curl openssh-client ca-certificates \
        && rm -rf /var/lib/apt/lists/*; \
    elif command -v apk >/dev/null 2>&1; then \
        apk add --no-cache git curl openssh-client ca-certificates; \
    elif command -v yum >/dev/null 2>&1; then \
        yum install -y git curl openssh-clients ca-certificates; \
    else \
        echo "错误：基础镜像内既无 git/curl，也没有可用包管理器，请更换 BASE_IMAGE" >&2; \
        exit 1; \
    fi; \
    git --version && curl --version

# 挂载目录
VOLUME /home/inks
RUN mkdir -p /home/inks
WORKDIR /home/inks

# 时区：应用日志时间取自 -Duser.timezone，容器内 date 等命令取自 TZ
ENV TZ=Asia/Shanghai

# 额外启动参数，如 -e PARAMS="--server.port=8080"
ENV PARAMS=""

# 复制已打包的 jar（jar 内已含前后端：后端 + classpath:/static/ 下的前端产物 + prod 配置）
COPY ./target/bizlens-ai-service-*.jar /home/inks/app.jar

# 前后端同源，只暴露一个端口
EXPOSE 10610

# 健康检查：调探针接口，docker compose ps 会显示 healthy/unhealthy
# 注意探针在数据库不可用时返回 status=DEGRADED 但 HTTP 仍为 200，
# 所以这里的 healthy 只代表 Web 服务在响应，数据库状态请看返回体。
# 端口写死 10610：若用 PARAMS 改了容器端口，需同步改这一行
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:10610/api/system/health || exit 1

# 启动脚本（先去掉可能存在的 CR：CRLF 会让 shebang 与 set 命令失效）
COPY ./entrypoint.sh /usr/local/bin/
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint.sh && chmod +x /usr/local/bin/entrypoint.sh
ENTRYPOINT ["entrypoint.sh"]
