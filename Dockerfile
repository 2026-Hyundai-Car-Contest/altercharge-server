# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY altercharger-server/gradlew ./gradlew
COPY altercharger-server/gradle ./gradle
COPY altercharger-server/build.gradle altercharger-server/settings.gradle ./
COPY altercharger-server/src ./src
RUN chmod +x gradlew \
    && ./gradlew --no-daemon bootJar -x test \
    && cp build/libs/*.jar /tmp/app.jar

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app \
    && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=build --chown=app:app /tmp/app.jar ./app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
