package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BackendContainerContractTest {

  private static final Path BACKEND_ROOT = locateBackendRoot();

  @Test
  void dockerfileUsesJava17BuildAndRuntimeImagesWithoutBakingSecrets() throws IOException {
    String dockerfile = Files.readString(BACKEND_ROOT.resolve("Dockerfile"));

    assertThat(dockerfile)
        .contains("FROM eclipse-temurin:17-jdk-jammy AS build")
        .contains("FROM eclipse-temurin:17-jre-jammy")
        .contains("./mvnw -q -DskipTests dependency:go-offline")
        .contains("./mvnw -q -DskipTests package")
        .contains("EXPOSE 8080")
        .contains("ENTRYPOINT [\"java\", \"-jar\", \"/app/app.jar\"]")
        .contains("USER volunteerflow")
        .doesNotContain("DB_PASSWORD")
        .doesNotContain("JWT_SECRET")
        .doesNotContain("application-local.yml");
  }

  @Test
  void buildStageWarmsTheSurefireProviderBeforeFixtureRuntime() throws IOException {
    String dockerfile = Files.readString(BACKEND_ROOT.resolve("Dockerfile"));

    int providerWarmup =
        dockerfile.indexOf("./mvnw -q -Dtest=Stage4dFixtureIdentityTest test");
    int packageBuild = dockerfile.indexOf("./mvnw -q -DskipTests package");

    assertThat(providerWarmup).isGreaterThanOrEqualTo(0);
    assertThat(packageBuild).isGreaterThan(providerWarmup);
  }

  @Test
  void dockerContextExcludesLocalConfigurationBuildOutputAndSecrets() throws IOException {
    String dockerignore = Files.readString(BACKEND_ROOT.resolve(".dockerignore"));

    assertThat(dockerignore)
        .contains("target/")
        .contains("config/application-local.yml")
        .contains("*.log")
        .contains(".idea/")
        .contains(".env")
        .contains(".env.*");
  }

  @Test
  void dockerfileNormalizesWindowsMavenWrapperBeforeExecutingIt() throws IOException {
    String dockerfile = Files.readString(BACKEND_ROOT.resolve("Dockerfile"));

    int normalization = dockerfile.indexOf("sed -i 's/\\r$//' mvnw");
    int firstWrapperExecution = dockerfile.indexOf("./mvnw");

    assertThat(normalization).isGreaterThanOrEqualTo(0);
    assertThat(firstWrapperExecution).isGreaterThan(normalization);
  }

  @Test
  void dependencyWarmupRetriesTransientRepositoryFailuresWithoutLoopingForever()
      throws IOException {
    String dockerfile = Files.readString(BACKEND_ROOT.resolve("Dockerfile"));

    assertThat(dockerfile)
        .contains("for attempt in 1 2 3; do")
        .contains("./mvnw -q -DskipTests dependency:go-offline && break")
        .contains("if [ \"$attempt\" -eq 3 ]; then exit 1; fi")
        .contains("sleep 5");
  }

  @Test
  void readinessIsDatabaseBackedButDoesNotRequireRedis() throws IOException {
    String application =
        Files.readString(BACKEND_ROOT.resolve("src/main/resources/application.yml"));

    assertThat(application)
        .contains("readiness:")
        .contains("include: readinessState,db")
        .doesNotContain("include: readinessState,db,redis");
  }

  private static Path locateBackendRoot() {
    Path workingDirectory = Path.of(System.getProperty("user.dir"));
    if (Files.isRegularFile(workingDirectory.resolve("pom.xml"))) {
      return workingDirectory;
    }

    Path backendDirectory = workingDirectory.resolve("backend");
    if (Files.isRegularFile(backendDirectory.resolve("pom.xml"))) {
      return backendDirectory;
    }

    throw new IllegalStateException("Cannot locate the backend module from " + workingDirectory);
  }
}
