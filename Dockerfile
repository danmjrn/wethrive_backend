# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="WeThrive Backend" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.vendor="Shape It Solutions"
RUN addgroup -S wethrive && adduser -S -G wethrive -u 10001 wethrive
WORKDIR /app
COPY --from=build --chown=wethrive:wethrive /build/target/wethrive-backend-*.jar app.jar
USER 10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.awt.headless=true", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/app.jar"]
