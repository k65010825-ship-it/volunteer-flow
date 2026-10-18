FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw \
    && chmod +x mvnw \
    && for attempt in 1 2 3; do \
         ./mvnw -q -DskipTests dependency:go-offline && break; \
         if [ "$attempt" -eq 3 ]; then exit 1; fi; \
         sleep 5; \
       done
COPY src src
RUN ./mvnw -q -Dtest=Stage4dFixtureIdentityTest test \
    && ./mvnw -q -DskipTests package

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
