import java.io.InputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * SELECT-only proof from the exact isolated database; never reads payloads or credentials into
 * reports.
 */
public final class AcceptanceEvidence {
  @SuppressWarnings("unchecked")
  public static void main(String[] args) {
    try {
      if (args.length != 2) throw new IllegalArgumentException();
      Map<String, Object> config;
      try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
        config = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
      }
      Map<String, Object> spring = (Map<String, Object>) config.get("spring");
      Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
      Map<String, Object> volunteer = (Map<String, Object>) config.get("volunteerflow");
      String expected = String.valueOf(((Map<?, ?>) volunteer.get("acceptance")).get("database"));
      if (!expected.matches("vf_acceptance_[0-9a-f]{32}")) throw new IllegalArgumentException();
      StringBuilder report =
          new StringBuilder("{\n  \"database\": \"").append(expected).append("\"");
      try (Connection connection =
          DriverManager.getConnection(
              String.valueOf(datasource.get("url")),
              String.valueOf(datasource.get("username")),
              String.valueOf(datasource.get("password")))) {
        connection.setReadOnly(true);
        try (Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery("SELECT DATABASE()")) {
          if (!rows.next() || !expected.equals(rows.getString(1)))
            throw new IllegalArgumentException();
        }
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put(
            "successfulMigrations",
            "SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL");
        queries.put(
            "failedMigrations", "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0");
        queries.put("users", "SELECT COUNT(*) FROM app_user");
        queries.put("changedCredentialUsers", "SELECT COUNT(*) FROM app_user WHERE version>0");
        queries.put("finishedActivities", "SELECT COUNT(*) FROM activity WHERE status='FINISHED'");
        queries.put(
            "deletedPositions", "SELECT COUNT(*) FROM activity_position WHERE status='DELETED'");
        queries.put(
            "acceptedOffers", "SELECT COUNT(*) FROM promotion_offer WHERE status='ACCEPTED'");
        queries.put("checkinRecords", "SELECT COUNT(*) FROM checkin_record");
        queries.put("auditRecords", "SELECT COUNT(*) FROM audit_log");
        queries.put("outboxEvents", "SELECT COUNT(*) FROM outbox_event");
        queries.put(
            "publishedEvents", "SELECT COUNT(*) FROM outbox_event WHERE status='PUBLISHED'");
        queries.put("notifications", "SELECT COUNT(*) FROM notification");
        for (var entry : queries.entrySet()) {
          try (Statement sql = connection.createStatement();
              ResultSet rows = sql.executeQuery(entry.getValue())) {
            rows.next();
            report.append(",\n  \"").append(entry.getKey()).append("\": ").append(rows.getLong(1));
          }
        }
      }
      report.append("\n}\n");
      Files.writeString(Path.of(args[1]), report);
      System.out.println("ACCEPTANCE_EVIDENCE_WRITTEN=true");
    } catch (Exception failure) {
      System.out.println("ACCEPTANCE_EVIDENCE_FAILED=true");
      System.exit(2);
    }
  }
}
