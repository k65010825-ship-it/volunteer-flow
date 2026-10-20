package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class Stage5DeploymentContractTest {
  static final Path ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }

  @Test
  void kraftAndBothBackendsArePrivateAndNeverOwnExistingStores() throws Exception {
    Path compose = ROOT.resolve("deploy/stage5/docker-compose.yml");
    assertThat(compose).as("Task9A Compose must exist").exists();
    Map<String, Object> doc = new Yaml().load(Files.readString(compose));
    Map<String, Object> services = map(doc.get("services"));
    assertThat(services.keySet()).containsExactlyInAnyOrder("kafka", "backend-a", "backend-b");
    for (Object value : services.values()) {
      assertThat(map(value)).doesNotContainKeys("ports", "container_name", "network_mode", "privileged");
      assertThat(map(value)).containsKey("mem_limit");
    }
    Map<String, Object> kafka = map(services.get("kafka"));
    assertThat(map(kafka.get("environment")))
        .containsEntry("KAFKA_PROCESS_ROLES", "broker,controller")
        .containsEntry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:9093")
        .containsEntry("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://kafka:9092")
        .containsEntry("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m");
    assertThat(kafka).containsKey("volumes");
    for (String name : new String[] {"backend-a", "backend-b"}) {
      Map<String, Object> backend = map(services.get(name));
      Map<String, Object> env = map(backend.get("environment"));
      assertThat(env).containsEntry("SPRING_FLYWAY_ENABLED", "false")
          .containsEntry("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
          .containsEntry("MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE", "readinessState,db")
          .containsKeys("DB_HOST", "DB_PASSWORD", "REDIS_HOST", "JWT_SECRET", "RATE_LIMIT_KEY_SECRET",
              "KAFKA_ACTIVITY_TOPIC", "KAFKA_REGISTRATION_TOPIC", "KAFKA_CHECKIN_TOPIC",
              "KAFKA_CONSUMER_GROUP", "OUTBOX_BATCH_SIZE", "OUTBOX_LEASE", "OUTBOX_SEND_TIMEOUT",
              "OUTBOX_RETRY_INITIAL", "OUTBOX_RETRY_MAX", "OUTBOX_RETENTION",
              "PROMOTION_RECONCILIATION_BATCH_SIZE", "PROMOTION_RECONCILIATION_LEASE");
      assertThat(env.get("JAVA_TOOL_OPTIONS").toString()).contains("-Xmx");
      assertThat(backend).doesNotContainKey("depends_on"); // business startup cannot depend on Kafka
      assertThat(map(backend.get("healthcheck"))).containsKeys("timeout", "retries");
    }
  }

  @Test
  void exampleHasNoLiveCredentialsAndVerificationSqlIsReadOnlyAndManifestScoped() throws Exception {
    Path env = ROOT.resolve("deploy/stage5/.env.example");
    assertThat(env).exists();
    assertThat(Files.readString(env)).contains("SPRING_FLYWAY_ENABLED=false", "replace-with-")
        .doesNotContain("192.168.", "BEGIN PRIVATE KEY");
    String sql = Files.readString(ROOT.resolve("deploy/stage5/verify-stage5.sql"));
    assertThat(sql).contains("@stage5_event_id", "@stage5_position_id", "consumed_event",
        "notification", "outbox_event", "promotion_reconcile_task", "promotion_offer")
        .doesNotContain("DELETE ", "TRUNCATE ", "UPDATE ", "INSERT ", "ALTER ");
  }
}
