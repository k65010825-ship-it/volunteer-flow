import java.io.InputStream;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** SELECT-only audit of the recovered original schema; no migration, repair, or business writes. */
public final class OriginalUpgradeAudit {
  @SuppressWarnings("unchecked")
  public static void main(String[] args) {
    try {
      if (args.length != 1) throw new IllegalArgumentException();
      Map<String, Object> config;
      try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
        config = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
      }
      Map<String, Object> spring = (Map<String, Object>) config.get("spring");
      Map<String, Object> source = (Map<String, Object>) spring.get("datasource");
      DriverManager.setLoginTimeout(8);
      try (Connection connection =
          DriverManager.getConnection(
              String.valueOf(source.get("url")),
              String.valueOf(source.get("username")),
              String.valueOf(source.get("password")))) {
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try (Statement sql = connection.createStatement();
            ResultSet rows = sql.executeQuery("SELECT DATABASE()")) {
          if (!rows.next() || !"volunteer_flow".equals(rows.getString(1)))
            throw new IllegalArgumentException();
        }
        System.out.println("ORIGINAL_AUDIT=CONNECTED_READ_ONLY");
        for (String table :
            List.of(
                "app_user",
                "organization",
                "activity",
                "activity_position",
                "registration",
                "registration_cycle",
                "checkin_record",
                "notification",
                "outbox_event")) {
          System.out.println(
              "ROWS_" + table + "=" + scalar(connection, "SELECT COUNT(*) FROM `" + table + "`"));
        }
        try (PreparedStatement sql =
            connection.prepareStatement(
                "SELECT column_type,is_nullable,column_default FROM information_schema.columns"
                    + " WHERE table_schema=DATABASE() AND table_name='registration_cycle' AND"
                    + " column_name='attendance_status'")) {
          try (ResultSet rows = sql.executeQuery()) {
            if (rows.next())
              System.out.println(
                  "V9_ATTENDANCE_DEFINITION_MATCH="
                      + ("varchar(32)".equals(rows.getString(1))
                          && "NO".equals(rows.getString(2))
                          && "NOT_REQUIRED".equals(rows.getString(3))));
          }
        }
        Map<String, String> indexes = new LinkedHashMap<>();
        indexes.put("activity|idx_activity_status_end_id", "status,activity_end_at,id");
        indexes.put("registration_cycle|idx_cycle_activity_status_id", "activity_id,status,id");
        indexes.put("notification|idx_notification_user_created_id", "user_id,create_time,id");
        indexes.put(
            "checkin_session|idx_checkin_session_activity_status_close_id",
            "activity_id,status,closes_at,id");
        for (var index : indexes.entrySet()) {
          String[] parts = index.getKey().split("\\|");
          try (PreparedStatement sql =
              connection.prepareStatement(
                  "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM"
                      + " information_schema.statistics WHERE table_schema=DATABASE() AND"
                      + " table_name=? AND index_name=?")) {
            sql.setString(1, parts[0]);
            sql.setString(2, parts[1]);
            try (ResultSet rows = sql.executeQuery()) {
              rows.next();
              System.out.println(
                  "V9_INDEX_" + parts[1] + "_MATCH=" + index.getValue().equals(rows.getString(1)));
            }
          }
        }
        System.out.println(
            "INVALID_ATTENDANCE_VALUES="
                + scalar(
                    connection,
                    "SELECT COUNT(*) FROM registration_cycle WHERE attendance_status NOT IN"
                        + " ('NOT_REQUIRED','PENDING','CHECKED_IN','NO_SHOW')"));
        System.out.println(
            "V10_LEGACY_OUTBOX_ROWS=" + scalar(connection, "SELECT COUNT(*) FROM outbox_event"));
        System.out.println(
            "REGISTRATION_WITHOUT_ACTIVITY="
                + scalar(
                    connection,
                    "SELECT COUNT(*) FROM registration r LEFT JOIN activity a ON a.id=r.activity_id"
                        + " WHERE a.id IS NULL"));
        System.out.println(
            "REGISTRATION_WITHOUT_USER="
                + scalar(
                    connection,
                    "SELECT COUNT(*) FROM registration r LEFT JOIN app_user u ON u.id=r.user_id"
                        + " WHERE u.id IS NULL"));
        try (Statement sql = connection.createStatement();
            ResultSet rows =
                sql.executeQuery(
                    "SELECT version,success FROM flyway_schema_history ORDER BY installed_rank")) {
          while (rows.next()) {
            String version = rows.getString(1);
            if (version != null && version.matches("[0-9.]+"))
              System.out.println("HISTORY_" + version + "_SUCCESS=" + rows.getBoolean(2));
          }
        }
        connection.rollback();
      }
    } catch (Exception failure) {
      System.out.println("ORIGINAL_AUDIT=FAILED_WITHOUT_WRITES");
      System.exit(2);
    }
  }

  private static long scalar(Connection connection, String query) throws SQLException {
    try (Statement sql = connection.createStatement()) {
      sql.setQueryTimeout(10);
      try (ResultSet rows = sql.executeQuery(query)) {
        rows.next();
        return rows.getLong(1);
      }
    }
  }
}
