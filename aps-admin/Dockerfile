# =============================================================================
# 衡策 APS 后端镜像（Spring Boot fat jar）
#   构建上下文必须是仓库根目录（需要父 pom 与全部内部模块）：
#     docker build -f aps-admin/Dockerfile -t aps-admin:local .
#   构建用 Maven 镜像基于 eclipse-temurin 21 JDK；运行阶段使用 21 JRE。
# =============================================================================
# syntax=docker/dockerfile:1.7

# --------------------------- 构建阶段 ---------------------------------------
# 构建阶段使用 Temurin 21 JDK；Maven 从官方 Maven 镜像复制，避免在基础镜像内
# 额外下载安装 Maven（多阶段间 COPY 不产生运行阶段负担）。
FROM eclipse-temurin:21-jdk AS build
COPY --from=maven:3.9-eclipse-temurin-21 /usr/share/maven /usr/share/maven
ENV PATH="/usr/share/maven/bin:${PATH}"
WORKDIR /workspace

# 先只复制 POM，尽可能复用「依赖下载」层（模块 pom 变化时才会失效）
COPY pom.xml ./
COPY aps-common/pom.xml aps-common/
COPY aps-domain/pom.xml aps-domain/
COPY aps-infrastructure/pom.xml aps-infrastructure/
COPY aps-service/pom.xml aps-service/
COPY aps-web/pom.xml aps-web/
COPY aps-admin/pom.xml aps-admin/
COPY aps-solver/pom.xml aps-solver/
COPY aps-solver/solver-api/pom.xml aps-solver/solver-api/
COPY aps-solver/solver-dsl/pom.xml aps-solver/solver-dsl/
COPY aps-solver/solver-timefold/pom.xml aps-solver/solver-timefold/
COPY aps-solver/solver-ortools/pom.xml aps-solver/solver-ortools/

RUN mvn -B -ntp -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true \
    dependency:go-offline || true

# 再复制源码并打包（Checkstyle/PMD 交由 CI 静态检查 job 负责）
COPY aps-common/src aps-common/src
COPY aps-domain/src aps-domain/src
COPY aps-infrastructure/src aps-infrastructure/src
COPY aps-service/src aps-service/src
COPY aps-web/src aps-web/src
COPY aps-admin/src aps-admin/src
COPY aps-solver/solver-api/src aps-solver/solver-api/src
COPY aps-solver/solver-dsl/src aps-solver/solver-dsl/src
COPY aps-solver/solver-timefold/src aps-solver/solver-timefold/src
COPY aps-solver/solver-ortools/src aps-solver/solver-ortools/src

RUN mvn -B -ntp -DskipTests -Dcheckstyle.skip=true -Dpmd.skip=true package \
    && cp aps-admin/target/aps-admin-*.jar /workspace/app.jar

# --------------------------- 运行阶段 ---------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

# 非 root 运行；curl 供 HEALTHCHECK 使用
RUN groupadd --system --gid 1001 aps \
    && useradd --system --uid 1001 --gid aps --home-dir /app --no-create-home --shell /usr/sbin/nologin aps \
    && apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build --chown=aps:aps /workspace/app.jar /app/app.jar

USER aps
EXPOSE 8080

# 健康检查直接打业务健康接口（与 README 一致：GET /api/v1/health）
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
