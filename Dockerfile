# =====================================================================
#  hong-coach 镜像
#
#  两阶段构建：先用 Maven 编译出 fat jar，运行阶段只留 JRE 和 jar，
#  最终镜像不含 Maven 仓库和源码，小很多。
#
#  注意：引擎不在镜像里。仓库里那份是 Windows 的 .exe，Linux 跑不了，
#  而且 nnue 权重有 50MB。Linux 引擎由 deploy/fetch-engine.sh 下载到
#  宿主机 ./engine，再以卷的形式挂到 /opt/engine。
# =====================================================================
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# 先只拷 pom 把依赖拉全：依赖没变时这一层能命中缓存，改代码不用重下依赖。
# go-offline 只是预热缓存，个别插件解析不到也不影响后面的 package，故容错。
COPY pom.xml ./

# 构建期走阿里云 Maven 镜像（国内服务器拉依赖快很多，约 200MB）。
# 海外服务器或想用官方源：删掉下面这行即可。
COPY deploy/maven-settings.xml /root/.m2/settings.xml

RUN mvn -B -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -B -DskipTests package \
    && cp target/hong-coach-*.jar /build/app.jar

# ---------------------------------------------------------------------
# 运行镜像必须是 Ubuntu 24.04(noble)，不能用 22.04(jammy)：
# Pikafish 官方 Linux 二进制要求 GLIBC >= 2.38，而 jammy 只有 2.35，会直接报
#     libc.so.6: version `GLIBC_2.38' not found
# 引擎起不来的话，「电脑走一步」和复盘讲解就全废了。noble 是 2.39，实测可用。
FROM eclipse-temurin:17-jre-noble

# tzdata：连接串里用的是 Asia/Shanghai，容器里得有对应时区数据
RUN apt-get update \
    && apt-get install -y --no-install-recommends tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 -m appuser

ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    SERVER_PORT=8080 \
    ENGINE_PATH=/opt/engine

WORKDIR /app

COPY --from=build /build/app.jar /app/app.jar

# 引擎目录和日志目录都以卷的形式挂进来，容器重建后不丢
RUN mkdir -p /app/logs /opt/engine && chown -R appuser:appuser /app /opt/engine

USER appuser

EXPOSE 8080

# 镜像里没有 curl/wget，直接用 bash 探一下端口
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]

# 容器里只跑这一件事，内存上限交给 MaxRAMPercentage（默认取容器内存的 75%）
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
