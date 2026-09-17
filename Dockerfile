# Build Stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/libs.versions.toml gradle/
COPY core core
COPY analytics analytics
COPY storage storage
COPY proxy proxy
COPY server server

RUN chmod +x ./gradlew
RUN ./gradlew shadowJar --no-daemon

# Runtime Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S prism && adduser -S prism -G prism
USER prism

COPY --from=builder /app/server/build/libs/prism-llm-server-1.0.0-all.jar app.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
