FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && ./mvnw -q -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -q -DskipTests package

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && groupadd --system volunteerflow \
    && useradd --system --gid volunteerflow --home-dir /app --shell /usr/sbin/nologin volunteerflow \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build --chown=volunteerflow:volunteerflow \
    /workspace/target/volunteerflow-backend-0.0.1-SNAPSHOT.jar \
    /app/app.jar

USER volunteerflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
