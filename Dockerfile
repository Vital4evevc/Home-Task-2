# syntax=docker/dockerfile:1
# One Dockerfile for both services; pick one with --build-arg MODULE=job-api | job-worker.

# ---- build stage: compiles and packages the jar (no local JDK/Maven needed) ----
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY job-common/pom.xml job-common/
COPY job-api/pom.xml job-api/
COPY job-worker/pom.xml job-worker/
COPY job-common/src job-common/src
COPY job-api/src job-api/src
COPY job-worker/src job-worker/src
# -am also builds job-common. Tests need Docker (Testcontainers) and run separately:
#   docker compose --profile test run --rm tests
RUN --mount=type=cache,target=/root/.m2 \
    test -n "$MODULE" && mvn -B -q -pl "$MODULE" -am package -DskipTests

# ---- runtime stage: small JRE image, non-root ----
FROM eclipse-temurin:21-jre
ARG MODULE
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
