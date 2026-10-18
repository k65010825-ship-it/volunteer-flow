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

class Stage4dDeploymentContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();
  private static final Path COMPOSE = REPOSITORY_ROOT.resolve("deploy/stage4d/docker-compose.yml");
  private static final Path NGINX = REPOSITORY_ROOT.resolve("deploy/stage4d/nginx.conf");
  private static final Path EXAMPLE_ENV = REPOSITORY_ROOT.resolve("deploy/stage4d/.env.example");

  private static final Set<String> BACKEND_ENVIRONMENT =
      Set.of(
          "DB_HOST",
          "DB_PORT",
          "DB_NAME",
          "DB_USERNAME",
          "DB_PASSWORD",
          "JAVA_TOOL_OPTIONS",
          "JWT_SECRET",
          "SPRING_FLYWAY_ENABLED",
          "REDIS_HOST",
          "REDIS_PORT",
          "REDIS_PASSWORD",
          "REDIS_DATABASE",
          "REDIS_CONNECT_TIMEOUT",
          "REDIS_COMMAND_TIMEOUT",
          "DYNAMIC_CHECKIN_WINDOW",
          "DYNAMIC_CHECKIN_TTL",
          "DYNAMIC_CHECKIN_TOKEN_BYTES",
          "RATE_LIMIT_ENABLED",
          "RATE_LIMIT_KEY_SECRET",
          "RATE_LIMIT_LOGIN_IP_LIMIT",
          "RATE_LIMIT_LOGIN_IP_WINDOW",
          "RATE_LIMIT_LOGIN_USERNAME_LIMIT",
          "RATE_LIMIT_LOGIN_USERNAME_WINDOW",
          "RATE_LIMIT_REGISTRATION_LIMIT",
          "RATE_LIMIT_REGISTRATION_WINDOW",
          "RATE_LIMIT_PROMOTION_LIMIT",
          "RATE_LIMIT_PROMOTION_WINDOW",
          "RATE_LIMIT_STATIC_CHECKIN_LIMIT",
          "RATE_LIMIT_STATIC_CHECKIN_WINDOW",
          "RATE_LIMIT_DYNAMIC_CHECKIN_LIMIT",
          "RATE_LIMIT_DYNAMIC_CHECKIN_WINDOW",
          "RATE_LIMIT_LOCAL_LOGIN_MAX_ENTRIES",
          "RATE_LIMIT_LOCAL_LOGIN_CLEANUP_INTERVAL",
          "BOOTSTRAP_ADMIN_USERNAME",
          "BOOTSTRAP_ADMIN_PASSWORD",
          "BOOTSTRAP_ADMIN_REAL_NAME",
          "BOOTSTRAP_ADMIN_STUDENT_NUMBER",
          "BOOTSTRAP_ADMIN_CONTACT");

  @Test
  void composeRunsTwoPrivateBackendsBehindOnePublicNginx() throws IOException {
    String source = Files.readString(COMPOSE);
    Map<String, Object> document = loadCompose(source);
    Map<String, Object> services = map(document.get("services"));
    Map<String, Object> common = map(document.get("x-backend-common"));
    Map<String, Object> backendA = map(services.get("backend-a"));
    Map<String, Object> backendB = map(services.get("backend-b"));
    Map<String, Object> nginx = map(services.get("frontend-nginx"));

    assertThat(source)
        .contains("x-backend-common: &backend-common")
        .contains("<<: *backend-common");
    assertThat(services.keySet())
        .containsExactlyInAnyOrder("backend-a", "backend-b", "frontend-nginx", "fixture", "k6");
    assertThat(backendA).containsAllEntriesOf(common);
    assertThat(backendB).containsAllEntriesOf(common);
    assertThat(backendA).doesNotContainKeys("ports", "container_name");
    assertThat(backendB).doesNotContainKeys("ports", "container_name");
    assertThat(map(backendA.get("environment")).keySet()).containsExactlyInAnyOrderElementsOf(BACKEND_ENVIRONMENT);
    assertThat(map(backendB.get("environment"))).isEqualTo(map(backendA.get("environment")));
    assertThat(map(backendA.get("healthcheck"))).isNotEmpty();
    assertThat(map(backendB.get("healthcheck"))).isNotEmpty();

    Map<String, Object> dependencies = map(nginx.get("depends_on"));
    assertThat(map(dependencies.get("backend-a"))).containsEntry("condition", "service_healthy");
    assertThat(map(dependencies.get("backend-b"))).containsEntry("condition", "service_healthy");
    assertThat(list(nginx.get("ports"))).containsExactly("${STAGE4D_APP_PORT:-8088}:80");
    assertThat(services.entrySet().stream().filter(entry -> map(entry.getValue()).containsKey("ports")))
        .extracting(Map.Entry::getKey)
        .containsExactly("frontend-nginx");
  }

  @Test
  void toolServicesAreProfileGatedAndMountOnlyTheirRequiredArtifacts() throws IOException {
    Map<String, Object> services = map(loadCompose(Files.readString(COMPOSE)).get("services"));
    Map<String, Object> fixture = map(services.get("fixture"));
    Map<String, Object> k6 = map(services.get("k6"));

    assertThat(list(fixture.get("profiles"))).containsExactly("tools");
    assertThat(map(fixture.get("build"))).containsEntry("target", "build");
    assertThat(list(fixture.get("volumes")))
        .containsExactly("${STAGE4D_OUTPUT_DIR:?set STAGE4D_OUTPUT_DIR}:/stage4d-output");
    assertThat(list(k6.get("profiles"))).containsExactly("tools");
    assertThat(k6).containsEntry("image", "grafana/k6:0.54.0");
    assertThat(list(k6.get("volumes")))
        .containsExactly(
            "../../loadtest/stage4d:/scripts:ro",
            "${STAGE4D_OUTPUT_DIR:?set STAGE4D_OUTPUT_DIR}:/output");
  }

  @Test
  void nginxBalancesBothBackendsWithoutLoggingCredentials() throws IOException {
    String nginx = Files.readString(NGINX);

    assertThat(nginx)
        .contains("server backend-a:8080;")
        .contains("server backend-b:8080;")
        .contains("proxy_set_header X-Forwarded-For $remote_addr;")
        .contains("$upstream_addr")
        .contains("$request_time")
        .contains("$upstream_response_time")
        .contains("location /api/")
        .contains("location /actuator/")
        .contains("try_files $uri $uri/ /index.html")
        .doesNotContain("$http_authorization")
        .doesNotContain("$http_cookie")
        .doesNotContain("$request_body");
    assertThat(countOccurrences(nginx, "server backend-a:8080;")).isEqualTo(1);
    assertThat(countOccurrences(nginx, "server backend-b:8080;")).isEqualTo(1);
  }

  @Test
  void exampleConfigurationAndIgnoreRulesDoNotExposeVmOrRunSecrets() throws IOException {
    String example = Files.readString(EXAMPLE_ENV);
    String gitignore = Files.readString(REPOSITORY_ROOT.resolve(".gitignore"));

    assertThat(example)
        .contains("SPRING_FLYWAY_ENABLED=false")
        .contains("STAGE4D_MIN_AVAILABLE_KB=5242880")
        .contains("DB_HOST=replace-with-mysql-host")
        .contains("REDIS_HOST=replace-with-redis-host")
        .contains("JWT_SECRET=replace-with-standard-base64-secret")
        .contains("RATE_LIMIT_KEY_SECRET=replace-with-rate-limit-secret")
        .doesNotContain("192.168.");
    assertThat(gitignore)
        .contains("output/stage4d/")
        .contains("deploy/stage4d/.env")
        .contains("tokens-*.json")
        .contains("k6-raw-*.json")
        .contains("*.pem")
        .contains("*.key");
  }

  private static int countOccurrences(String value, String token) {
    int count = 0;
    int offset = 0;
    while ((offset = value.indexOf(token, offset)) >= 0) {
      count++;
      offset += token.length();
    }
    return count;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadCompose(String source) {
    return (Map<String, Object>) new Yaml().load(source);
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
