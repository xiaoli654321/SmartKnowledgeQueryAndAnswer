# ── Java 服务通用镜像 ────────────────────────────────────────
# 多阶段构建在这里没必要：jar 由 Maven 在 CI/本地先打好，
# 镜像只做"运行"这一件事，构建更快、镜像也更可控。
#
# 用法：
#   docker build -f deploy/docker/Dockerfile.java -t skqa/gateway:1.0.0 \
#     --build-arg JAR_FILE=gateway/target/gateway-0.0.1-SNAPSHOT.jar .
FROM eclipse-temurin:17-jre-jammy

ARG JAR_FILE
ARG APP_NAME=app

WORKDIR /app

# 时区：日志时间对不上，排查跨服务问题会疯
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

RUN apt-get update && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

COPY ${JAR_FILE} /app/app.jar

EXPOSE 8080

# 健康检查走 actuator，K8s 的 readiness/liveness 也用它
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:${SERVER_PORT:-8080}/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]