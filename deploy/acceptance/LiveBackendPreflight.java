import java.io.InputStream;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Standalone SELECT/metadata-only probe; never starts Spring, Flyway, or a Kafka consumer. */
public final class LiveBackendPreflight {
  public static void main(String[] args) {
    Map<String, Object> config;
    try {
      if (args.length != 1) throw new IllegalArgumentException();
      try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        config = new Yaml(new SafeConstructor(options)).load(input);
        if (config == null) throw new IllegalArgumentException();
      }
    } catch (Exception failure) {
      System.out.println("PREFLIGHT_CONFIG=UNAVAILABLE_OR_INVALID");
      return;
    }
    database(config);
    kafka(config);
  }

  private static void database(Map<String, Object> config) {
    String url = value(config, "spring.datasource.url", "");
    String username = value(config, "spring.datasource.username", "");
    String password = value(config, "spring.datasource.password", "");
    if (url.isBlank() || username.isBlank() || password.isBlank()) {
      System.out.println("DATABASE=REQUIRED_DATASOURCE_KEY_MISSING");
      return;
    }
    try {
      DriverManager.setLoginTimeout(8);
      try (Connection connection = DriverManager.getConnection(url, username, password)) {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        System.out.println("DATABASE=CONNECTED_READ_ONLY");
        String expectedDatabase =
            value(config, "volunteerflow.acceptance.database", "volunteer_flow");
        if (!expectedDatabase.equals("volunteer_flow")
            && !expectedDatabase.matches("vf_acceptance_[0-9a-f]{32}"))
          throw new IllegalArgumentException();
        boolean expected =
            scalar(
                    connection,
                    "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=DATABASE()"
                        + " AND schema_name=?",
                    expectedDatabase)
                == 1;
        System.out.println("DATABASE_EXPECTED_SCHEMA=" + expected);
        if (!expected) return;
        for (String table :
            List.of(
                "app_user",
                "organization",
                "registration_cycle",
                "checkin_session",
                "notification",
                "outbox_event",
                "consumed_event",
                "promotion_reconcile_task"))
          System.out.println(
              "TABLE_"
                  + table
                  + "="
                  + (scalar(
                          connection,
                          "SELECT COUNT(*) FROM information_schema.tables WHERE"
                              + " table_schema=DATABASE() AND table_name=?",
                          table)
                      == 1));
        for (String column :
            List.of(
                "topic",
                "message_key",
                "locked_by",
                "locked_until",
                "last_error",
                "published_partition",
                "published_offset"))
          System.out.println(
              "V10_OUTBOX_COLUMN_" + column + "=" + column(connection, "outbox_event", column));
        for (String column :
            List.of(
                "request_version",
                "processed_version",
                "status",
                "locked_by",
                "locked_until",
                "organization_id"))
          System.out.println(
              "V10_RECONCILE_COLUMN_"
                  + column
                  + "="
                  + column(connection, "promotion_reconcile_task", column));
        for (String index :
            List.of(
                "idx_outbox_event_status_retry_id",
                "idx_outbox_event_status_lease_id",
                "idx_outbox_event_published_at_id"))
          System.out.println(
              "V10_INDEX_"
                  + index
                  + "="
                  + (scalar(
                          connection,
                          "SELECT COUNT(*) FROM information_schema.statistics WHERE"
                              + " table_schema=DATABASE() AND table_name='outbox_event' AND"
                              + " index_name=?",
                          index)
                      > 0));
        System.out.println(
            "V9_ATTENDANCE_COLUMN="
                + column(connection, "registration_cycle", "attendance_status"));
        System.out.println(
            "V9_ACTIVITY_DUE_INDEX="
                + (scalar(
                        connection,
                        "SELECT COUNT(*) FROM information_schema.statistics WHERE"
                            + " table_schema=DATABASE() AND table_name='activity' AND"
                            + " index_name='idx_activity_status_end_id'")
                    == 3));
        if (scalar(
                connection,
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                    + " table_name='flyway_schema_history'")
            == 1) {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT version,success FROM flyway_schema_history WHERE version IN ('9','10')"
                      + " ORDER BY installed_rank")) {
            statement.setQueryTimeout(5);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next())
                System.out.println(
                    "FLYWAY_VERSION_" + rows.getString(1) + "_SUCCESS=" + rows.getBoolean(2));
            }
          }
        }
        connection.rollback();
      }
    } catch (Exception failure) {
      System.out.println("DATABASE=READ_ONLY_PROBE_FAILED");
    }
  }

  private static void kafka(Map<String, Object> config) {
    Properties properties = new Properties();
    properties.put(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
        System.getenv()
            .getOrDefault(
                "KAFKA_BOOTSTRAP_SERVERS",
                value(config, "volunteerflow.events.bootstrap-servers", "localhost:9092")));
    properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "live-backend-read-only-preflight");
    properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "3000");
    properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "5000");
    // Only metadata operations: never produce, subscribe, or create/delete topics/groups.
    try (Admin admin = Admin.create(properties)) {
      int brokers = admin.describeCluster().nodes().get(6, TimeUnit.SECONDS).size();
      Set<String> topics = admin.listTopics().names().get(6, TimeUnit.SECONDS);
      System.out.println("KAFKA_METADATA=AVAILABLE");
      System.out.println("KAFKA_BROKER_COUNT=" + brokers);
      for (String kind : List.of("activity", "registration", "checkin")) {
        String fallback =
            value(
                config,
                "volunteerflow.events.topics." + kind,
                "volunteerflow." + kind + "-events.v1");
        String topic =
            System.getenv()
                .getOrDefault("KAFKA_" + kind.toUpperCase(Locale.ROOT) + "_TOPIC", fallback);
        System.out.println("KAFKA_TOPIC_" + kind + "=" + topics.contains(topic));
      }
      admin.close(Duration.ofSeconds(1));
    } catch (Exception failure) {
      System.out.println("KAFKA_METADATA=UNAVAILABLE");
    }
  }

  private static boolean column(Connection connection, String table, String column)
      throws SQLException {
    return scalar(
            connection,
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                + " table_name=? AND column_name=?",
            table,
            column)
        == 1;
  }

  private static long scalar(Connection connection, String sql, String... parameters)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setQueryTimeout(5);
      for (int i = 0; i < parameters.length; i++) statement.setString(i + 1, parameters[i]);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }

  private static String value(Map<String, Object> config, String path, String fallback) {
    Object node = config;
    for (String part : path.split("\\.")) {
      if (!(node instanceof Map<?, ?> map) || !map.containsKey(part)) return fallback;
      node = map.get(part);
    }
    if (!(node instanceof String text)) return node == null ? fallback : String.valueOf(node);
    if (text.startsWith("${") && text.endsWith("}")) {
      String inner = text.substring(2, text.length() - 1);
      int separator = inner.indexOf(':');
      return System.getenv()
          .getOrDefault(
              separator < 0 ? inner : inner.substring(0, separator),
              separator < 0 ? fallback : inner.substring(separator + 1));
    }
    return text;
  }
}
