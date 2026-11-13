# syntax=docker/dockerfile:1
# Multi-stage build for notification-service (Independent Gradle Project)
FROM eclipse-temurin:17-jdk-jammy AS builder
WORKDIR /app

COPY . .
RUN chmod +x ./gradlew
# The Gradle cache is shared by all services; locked, because parallel builds corrupt the wrapper download
RUN --mount=type=cache,target=/root/.gradle,sharing=locked ./gradlew bootJar --no-daemon -x test && rm -f build/libs/*-plain.jar

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8087

ENTRYPOINT ["java", "-jar", "app.jar"]
