# Build: docker build -t doorprints-api backend  (the Doorprints API)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
# Run as an unprivileged user (threat model F-23). Fixed UID so volume permissions are predictable.
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app
COPY --from=build --chown=10001:10001 /src/target/*.jar app.jar
USER 10001:10001
# Keeps memory low enough for free-tier hosts (512 MB-1 GB); exit (and let the platform restart) on OOM.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# The JRE image has no curl/wget, so probe with bash's /dev/tcp. /actuator/health is public and rate-limit exempt.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf 'GET /actuator/health HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && grep -q '\"UP\"' <&3"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
