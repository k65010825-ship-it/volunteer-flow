package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class DeploymentComposeContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @Test
  void composeOwnsOnlyBackendAndFrontendAndPublishesOnlyNginx() throws IOException {
    Map<String, Object> compose = loadCompose();
    Map<String, Object> services = map(compose.get("services"));

    assertThat(services.keySet()).containsExactlyInAnyOrder("backend", "frontend-nginx");
    assertThat(compose).doesNotContainKeys("volumes");

    Map<String, Object> backend = map(services.get("backend"));
    assertThat(map(backend.get("build"))).containsEntry("context", "../backend");
    assertThat(backend).doesNotContainKeys("ports", "volumes");

    Map<String, Object> frontend = map(services.get("frontend-nginx"));
    assertThat(map(frontend.get("build")))
        .containsEntry("context", "..")
        .containsEntry("dockerfile", "frontend/Dockerfile");
    assertThat(list(frontend.get("ports"))).containsExactly("${APP_PORT:-8088}:80");
    assertThat(frontend).doesNotContainKey("volumes");
  }

  @Test
  void backendReceivesRequiredSettingsWithoutGeneratedSecretConfiguration() throws IOException {
    Map<String, Object> backend = map(map(loadCompose().get("services")).get("backend"));
    Map<String, Object> environment = map(backend.get("environment"));

    assertThat(environment)
        .containsEntry("DB_HOST", "${DB_HOST:?set DB_HOST}")
        .containsEntry("DB_PORT", "${DB_PORT:?set DB_PORT}")
        .containsEntry("DB_NAME", "${DB_NAME:?set DB_NAME}")
        .containsEntry("DB_USERNAME", "${DB_USERNAME:?set DB_USERNAME}")
        .containsEntry("DB_PASSWORD", "${DB_PASSWORD:?set DB_PASSWORD}")
        .containsEntry("JWT_SECRET", "${JWT_SECRET:?set JWT_SECRET}")
        .containsEntry("BOOTSTRAP_ADMIN_USERNAME", "${BOOTSTRAP_ADMIN_USERNAME:-}")
        .containsEntry("BOOTSTRAP_ADMIN_PASSWORD", "${BOOTSTRAP_ADMIN_PASSWORD:-}")
        .containsEntry("BOOTSTRAP_ADMIN_REAL_NAME", "${BOOTSTRAP_ADMIN_REAL_NAME:-}")
        .containsEntry("BOOTSTRAP_ADMIN_STUDENT_NUMBER", "${BOOTSTRAP_ADMIN_STUDENT_NUMBER:-}")
        .containsEntry("BOOTSTRAP_ADMIN_CONTACT", "${BOOTSTRAP_ADMIN_CONTACT:-}");
    assertThat(environment.keySet())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                "DB_HOST",
                "DB_PORT",
                "DB_NAME",
                "DB_USERNAME",
                "DB_PASSWORD",
                "JWT_SECRET",
                "BOOTSTRAP_ADMIN_USERNAME",
                "BOOTSTRAP_ADMIN_PASSWORD",
                "BOOTSTRAP_ADMIN_REAL_NAME",
                "BOOTSTRAP_ADMIN_STUDENT_NUMBER",
                "BOOTSTRAP_ADMIN_CONTACT"));
  }

  @Test
  void nginxWaitsForDatabaseBackedReadiness() throws IOException {
    Map<String, Object> services = map(loadCompose().get("services"));
    Map<String, Object> backend = map(services.get("backend"));
    Map<String, Object> healthcheck = map(backend.get("healthcheck"));
    Map<String, Object> frontend = map(services.get("frontend-nginx"));

    assertThat(list(healthcheck.get("test")))
        .containsExactly(
            "CMD", "curl", "--fail", "http://localhost:8080/actuator/health/readiness");
    assertThat(healthcheck)
        .containsEntry("interval", "10s")
        .containsEntry("timeout", "5s")
        .containsEntry("retries", 12)
        .containsEntry("start_period", "30s");
    assertThat(map(map(frontend.get("depends_on")).get("backend")))
        .containsEntry("condition", "service_healthy");
  }

  @Test
  void exampleEnvironmentContainsOnlyPlaceholdersAndRealEnvironmentIsIgnored() throws IOException {
    String example = Files.readString(REPOSITORY_ROOT.resolve("deploy/.env.example"));
    String gitignore = Files.readString(REPOSITORY_ROOT.resolve(".gitignore"));

    assertThat(example)
        .contains("DB_HOST='replace-with-vm-host-or-reachable-mysql-dns'")
        .contains("DB_PORT='replace-with-mysql-host-port'")
        .contains("DB_NAME='volunteer_flow'")
        .contains("DB_USERNAME='replace-with-database-user'")
        .contains("DB_PASSWORD='replace-with-database-password'")
        .contains("JWT_SECRET='replace-with-standard-base64-of-at-least-32-random-bytes'")
        .contains("standard Base64 encoding of at least 32 random bytes")
        .contains("BOOTSTRAP_ADMIN_USERNAME=''")
        .contains("BOOTSTRAP_ADMIN_PASSWORD=''")
        .contains("Keep single quotes around free-form values")
        .contains("APP_PORT=8088")
        .doesNotContain("DB_PORT=3306")
        .doesNotContain("192.168.")
        .doesNotContain("127.0.0.1");
    assertThat(gitignore).contains("deploy/.env");
  }

  @Test
  void smokeScriptFailsClosedAcrossReadinessRootAndDeepLink() throws IOException {
    String smoke = Files.readString(REPOSITORY_ROOT.resolve("deploy/smoke-test.ps1"));

    assertThat(smoke)
        .contains("$ErrorActionPreference = 'Stop'")
        .contains("/actuator/health/readiness")
        .contains("backend readiness is not UP")
        .contains("frontend root failed")
        .contains("/notifications")
        .contains("SPA fallback failed")
        .contains("-TimeoutSec")
        .doesNotContain("Authorization")
        .doesNotContain("$env:")
        .doesNotContain("Format-List");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadCompose() throws IOException {
    Object value =
        new Yaml().load(Files.readString(REPOSITORY_ROOT.resolve("deploy/docker-compose.yml")));
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  private static Path locateRepositoryRoot() {
    Path workingDirectory = Path.of(System.getProperty("user.dir"));
    if (Files.isRegularFile(workingDirectory.resolve("backend/pom.xml"))) {
      return workingDirectory;
    }
    if (Files.isRegularFile(workingDirectory.resolve("pom.xml"))) {
      return workingDirectory.getParent();
    }
    throw new IllegalStateException("Cannot locate repository root from " + workingDirectory);
  }
}
