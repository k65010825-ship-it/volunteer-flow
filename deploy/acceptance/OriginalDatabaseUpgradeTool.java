import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Explicit backup → verified clone → canonical V9 replay → V10, never blind history editing. */
public final class OriginalDatabaseUpgradeTool {
  static final ObjectMapper JSON = new ObjectMapper();
  static final String ORIGINAL = "volunteer_flow";
  static final String PRESERVED = "vf9_preserved_attendance_status";
  static final String PRESERVED_TIME = "vf9_preserved_update_time";
  static final Map<String, String> V9_INDEXES =
      Map.of(
          "activity", "idx_activity_status_end_id",
          "registration_cycle", "idx_cycle_activity_status_id",
          "notification", "idx_notification_user_created_id",
          "checkin_session", "idx_checkin_session_activity_status_close_id");
  static String stage = "ARGUMENTS";
  final Path directory;
  final Path migrations;
  final Map<String, Object> config;
  final String url, username, password;
  final String runId, clone;

  public record TableImage(List<String> columns, List<String> keys, long rows, String digest) {}

  public record Snapshot(Map<String, TableImage> tables) {}

  public record State(String runId, String cloneDatabase, String dumpSha256, Snapshot baseline) {}

  public record Proof(
      String runId, String cloneDatabase, String dumpSha256, String result, String finishedAt) {}

  @SuppressWarnings("unchecked")
  OriginalDatabaseUpgradeTool(Path input, Path directory, Path migrations, String runId)
      throws Exception {
    this.directory = directory.toRealPath();
    this.migrations = migrations.toRealPath();
    this.runId = runId;
    clone = UpgradeSafety.cloneName(runId);
    try (InputStream source = Files.newInputStream(input)) {
      config = new Yaml(new SafeConstructor(new LoaderOptions())).load(source);
    }
    var datasource = (Map<String, Object>) ((Map<?, ?>) config.get("spring")).get("datasource");
    url = String.valueOf(datasource.get("url"));
    username = String.valueOf(datasource.get("username"));
    password = String.valueOf(datasource.get("password"));
    UpgradeSafety.cloneUrl(url, clone);
  }

  public static void main(String[] args) {
    // Flyway diagnostics are intentionally private; this tool reports fixed stages only.
    ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("ROOT"))
        .setLevel(ch.qos.logback.classic.Level.OFF);
    try {
      if (args.length < 5) throw new IllegalArgumentException();
      var tool =
          new OriginalDatabaseUpgradeTool(
              Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4]);
      switch (args[0]) {
        case "backup-clone" -> tool.backupClone();
        case "restore-verify-empty-clone" -> tool.restoreVerifyEmptyClone();
        case "rehearse" -> tool.rehearse();
        case "check-original-gate" -> tool.checkOriginalGate();
        case "verify-upgraded-original" -> tool.verifyUpgradedOriginal();
        case "apply-original" -> {
          if (args.length != 6 || !"APPLY_AFTER_VERIFIED_BACKUP_AND_REHEARSAL".equals(args[5]))
            throw new IllegalArgumentException();
          tool.applyOriginal();
        }
        default -> throw new IllegalArgumentException();
      }
    } catch (Exception failure) {
      System.out.println("UPGRADE_FAILED_STAGE=" + stage);
      System.out.println("FAILURE_CLASS=" + failure.getClass().getSimpleName());
      if (failure instanceof SQLException sql)
        System.out.println("SQLSTATE=" + sql.getSQLState() + ";VENDOR=" + sql.getErrorCode());
      System.exit(2);
    }
  }

  Connection connect(String database, boolean readOnly) throws Exception {
    String target = ORIGINAL.equals(database) ? url : UpgradeSafety.cloneUrl(url, database);
    Connection connection = DriverManager.getConnection(target, username, password);
    connection.setReadOnly(readOnly);
    try (Statement sql = connection.createStatement();
        ResultSet rows = sql.executeQuery("SELECT DATABASE()")) {
      if (!rows.next() || !database.equals(rows.getString(1))) {
        connection.close();
        throw new IllegalStateException();
      }
    }
    return connection;
  }

  void backupClone() throws Exception {
    Path dump = directory.resolve("original-full.sql");
    Path stateFile = directory.resolve("upgrade-state.json");
    if (Files.exists(dump) || Files.exists(stateFile)) throw new IllegalStateException();
    stage = "ORIGINAL_PREFLIGHT";
    Snapshot before;
    try (Connection source = connect(ORIGINAL, true)) {
      // Abort rather than perform an incomplete/custom backup of objects requiring special review.
      for (String query :
          List.of(
              "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                  + " table_type<>'BASE TABLE'",
              "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                  + " engine<>'InnoDB'",
              "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE()",
              "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()",
              "SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE()"))
        if (scalar(source, query) != 0) throw new IllegalStateException();
      if (scalar(source, "SELECT COUNT(*) FROM outbox_event") != 0)
        throw new IllegalStateException();
      before = snapshot(source, null);
    }
    Path options = writeClientOptions();
    stage = "CONSISTENT_MYSQLDUMP";
    execute(
        List.of(
            mysqlBinary("mysqldump.exe"),
            "--defaults-file=" + options.getFileName(),
            "--single-transaction",
            "--routines",
            "--events",
            "--triggers",
            "--hex-blob",
            "--column-statistics=0",
            "--set-gtid-purged=OFF",
            "--no-tablespaces",
            "--default-character-set=utf8mb4",
            "--result-file=" + dump,
            ORIGINAL),
        null,
        "dump");
    UpgradeSafety.requireSafeDump(Files.readString(dump));
    stage = "BACKUP_SOURCE_STABILITY";
    try (Connection source = connect(ORIGINAL, true)) {
      requireEqual(before, snapshot(source, before), false);
    }
    stage = "CREATE_EMPTY_CLONE";
    try (Connection source = connect(ORIGINAL, false)) {
      try (PreparedStatement query =
          source.prepareStatement(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
        query.setString(1, clone);
        try (ResultSet rows = query.executeQuery()) {
          rows.next();
          if (rows.getLong(1) != 0) throw new IllegalStateException();
        }
      }
      try (Statement sql = source.createStatement()) {
        // This is the ONLY write in the backup action: creates the exact absent clone, never source
        // tables.
        sql.executeUpdate(
            "CREATE DATABASE `" + clone + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      }
    }
    stage = "RESTORE_CLONE_ONLY";
    execute(
        List.of(
            mysqlBinary("mysql.exe"),
            "--defaults-file=" + options.getFileName(),
            "--default-character-set=utf8mb4",
            "--binary-mode",
            "--database=" + clone),
        dump,
        "restore");
    stage = "VERIFY_ALL_RESTORED_ROWS";
    try (Connection restored = connect(clone, true)) {
      requireEqual(before, snapshot(restored, before), false);
    }
    State state = new State(runId, clone, sha256(dump), before);
    JSON.writeValue(stateFile.toFile(), state);
    Files.writeString(directory.resolve("original-full.sql.sha256"), state.dumpSha256() + "\n");
    System.out.println("CONSISTENT_BACKUP_AND_RESTORE=PASSED");
    System.out.println("CLONE_DATABASE=" + clone);
    System.out.println("TABLES_VERIFIED=" + before.tables().size());
  }

  void rehearse() throws Exception {
    State state = verifiedState();
    stage = "CLONE_BASELINE_CHECK";
    try (Connection connection = connect(clone, true)) {
      requireEqual(state.baseline(), snapshot(connection, state.baseline()), false);
    }
    upgrade(clone, state.baseline());
    JSON.writeValue(
        directory.resolve("rehearsal-passed.json").toFile(),
        new Proof(
            runId, clone, state.dumpSha256(), "CLONE_UPGRADE_PASSED", Instant.now().toString()));
    prepareRuntime();
    System.out.println("CLONE_UPGRADE=PASSED");
    System.out.println("BUSINESS_ROW_FINGERPRINTS=PRESERVED");
  }

  void restoreVerifyEmptyClone() throws Exception {
    if (Files.exists(directory.resolve("upgrade-state.json"))) throw new IllegalStateException();
    stage = "VERIFY_EMPTY_OWNED_CLONE";
    try (Connection restored = connect(clone, true)) {
      if (scalar(
              restored,
              "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")
          != 0) throw new IllegalStateException();
    }
    Snapshot before;
    try (Connection source = connect(ORIGINAL, true)) {
      before = snapshot(source, null);
    }
    Path dump = directory.resolve("original-full.sql");
    UpgradeSafety.requireSafeDump(Files.readString(dump));
    stage = "RESTORE_EMPTY_CLONE_RELATIVE_OPTIONS";
    execute(
        List.of(
            mysqlBinary("mysql.exe"),
            "--defaults-file=mysql-client.cnf",
            "--default-character-set=utf8mb4",
            "--binary-mode",
            "--database=" + clone),
        dump,
        "restore-relative");
    stage = "VERIFY_ALL_RESTORED_ROWS";
    try (Connection restored = connect(clone, true)) {
      requireEqual(before, snapshot(restored, before), false);
    }
    try (Connection source = connect(ORIGINAL, true)) {
      requireEqual(before, snapshot(source, before), false);
    }
    State state = new State(runId, clone, sha256(dump), before);
    JSON.writeValue(directory.resolve("upgrade-state.json").toFile(), state);
    Files.writeString(directory.resolve("original-full.sql.sha256"), state.dumpSha256() + "\n");
    System.out.println("CONSISTENT_BACKUP_AND_RESTORE=PASSED");
    System.out.println("CLONE_DATABASE=" + clone);
    System.out.println("TABLES_VERIFIED=" + before.tables().size());
  }

  void applyOriginal() throws Exception {
    State state = verifiedState();
    Proof proof = JSON.readValue(directory.resolve("rehearsal-passed.json").toFile(), Proof.class);
    if (!runId.equals(proof.runId())
        || !clone.equals(proof.cloneDatabase())
        || !state.dumpSha256().equals(proof.dumpSha256())
        || !"CLONE_UPGRADE_PASSED".equals(proof.result())) throw new IllegalStateException();
    stage = "ORIGINAL_MAINTENANCE_AND_FINGERPRINT_GATE";
    try (Connection source = connect(ORIGINAL, true)) {
      requireEqual(state.baseline(), snapshot(source, state.baseline()), false);
      long otherSessions =
          scalar(
              source,
              "SELECT COUNT(*) FROM information_schema.processlist WHERE db=DATABASE() AND"
                  + " id<>CONNECTION_ID()");
      if (!UpgradeSafety.allowOriginal(true, true, true, otherSessions))
        throw new IllegalStateException();
    }
    upgrade(ORIGINAL, state.baseline());
    JSON.writeValue(
        directory.resolve("original-upgrade-passed.json").toFile(),
        new Proof(
            runId,
            ORIGINAL,
            state.dumpSha256(),
            "ORIGINAL_UPGRADE_PASSED",
            Instant.now().toString()));
    System.out.println("ORIGINAL_UPGRADE=PASSED");
    System.out.println("BUSINESS_ROW_FINGERPRINTS=PRESERVED");
  }

  void checkOriginalGate() throws Exception {
    State state = verifiedState();
    stage = "READ_ONLY_ORIGINAL_GATE";
    try (Connection source = connect(ORIGINAL, true)) {
      requireEqual(state.baseline(), snapshot(source, state.baseline()), false);
      System.out.println("ORIGINAL_BASELINE_UNCHANGED=true");
      System.out.println(
          "OTHER_ORIGINAL_SESSIONS="
              + scalar(
                  source,
                  "SELECT COUNT(*) FROM information_schema.processlist WHERE db=DATABASE() AND"
                      + " id<>CONNECTION_ID()"));
      System.out.println(
          "OTHER_ORIGINAL_TRANSACTIONS="
              + scalar(
                  source,
                  "SELECT COUNT(*) FROM information_schema.innodb_trx t JOIN"
                      + " information_schema.processlist p ON p.id=t.trx_mysql_thread_id WHERE"
                      + " p.db=DATABASE() AND p.id<>CONNECTION_ID()"));
    }
  }

  void verifyUpgradedOriginal() throws Exception {
    State state = verifiedState();
    stage = "READ_ONLY_POST_UPGRADE_VERIFICATION";
    try (Connection source = connect(ORIGINAL, true)) {
      requireEqual(state.baseline(), snapshot(source, state.baseline()), true);
      if (scalar(
                  source,
                  "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('9','10') AND"
                      + " success=1")
              != 2
          || scalar(source, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0") != 0)
        throw new IllegalStateException();
    }
    flyway(url, "10").validate();
    System.out.println("ORIGINAL_V9_V10_VALIDATION=PASSED");
    System.out.println("ORIGINAL_BUSINESS_FINGERPRINTS=PRESERVED");
  }

  void upgrade(String database, Snapshot baseline) throws Exception {
    String targetUrl = ORIGINAL.equals(database) ? url : UpgradeSafety.cloneUrl(url, database);
    Flyway v9 = flyway(targetUrl, "9");
    stage = "VALIDATE_ONLY_FAILED_V9";
    ValidateResult validation = v9.validateWithResult();
    if (validation.validationSuccessful
        || validation.invalidMigrations.size() != 1
        || !"9".equals(validation.invalidMigrations.get(0).version)
        || !"FAILED_VERSIONED_MIGRATION"
            .equals(validation.invalidMigrations.get(0).errorDetails.errorCode.toString()))
      throw new IllegalStateException();
    try (Connection connection = connect(database, false)) {
      if (scalar(connection, "SELECT COUNT(*) FROM outbox_event") != 0)
        throw new IllegalStateException();
      stage = "PRESERVE_EXISTING_ATTENDANCE_COLUMN";
      try (Statement sql = connection.createStatement()) {
        // Rewind only V9 schema objects; retain every existing attendance value under a temporary
        // name.
        sql.executeUpdate(
            "ALTER TABLE registration_cycle RENAME COLUMN attendance_status TO " + PRESERVED);
        sql.executeUpdate(
            "ALTER TABLE registration_cycle ADD COLUMN " + PRESERVED_TIME + " DATETIME(6) NULL");
        sql.executeUpdate(
            "UPDATE registration_cycle SET "
                + PRESERVED_TIME
                + "=update_time,update_time=update_time");
        for (var entry : V9_INDEXES.entrySet())
          sql.executeUpdate(
              "ALTER TABLE `" + entry.getKey() + "` DROP INDEX `" + entry.getValue() + "`");
      }
    }
    stage = "FLYWAY_REPAIR_FAILED_V9_ONLY";
    var repair = v9.repair();
    if (repair.migrationsRemoved.size() != 1
        || !repair.migrationsAligned.isEmpty()
        || !repair.migrationsDeleted.isEmpty()) throw new IllegalStateException();
    stage = "REPLAY_UNMODIFIED_V9";
    v9.migrate();
    stage = "RESTORE_EXISTING_ATTENDANCE_VALUES";
    try (Connection connection = connect(database, false);
        Statement sql = connection.createStatement()) {
      sql.executeUpdate(
          "UPDATE registration_cycle SET attendance_status="
              + PRESERVED
              + ",update_time="
              + PRESERVED_TIME);
      sql.executeUpdate(
          "ALTER TABLE registration_cycle DROP COLUMN "
              + PRESERVED
              + ",DROP COLUMN "
              + PRESERVED_TIME);
    }
    stage = "MIGRATE_CANONICAL_V10";
    Flyway complete = flyway(targetUrl, "10");
    complete.migrate();
    complete.validate();
    stage = "VERIFY_UPGRADED_BUSINESS_DATA";
    try (Connection connection = connect(database, true)) {
      requireEqual(baseline, snapshot(connection, baseline), true);
      if (scalar(
                  connection,
                  "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('9','10') AND"
                      + " success=1")
              != 2
          || scalar(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0") != 0)
        throw new IllegalStateException();
    }
  }

  Flyway flyway(String targetUrl, String target) {
    return Flyway.configure()
        .dataSource(targetUrl, username, password)
        .locations("filesystem:" + migrations.toString().replace('\\', '/'))
        .cleanDisabled(true)
        .baselineOnMigrate(false)
        .target(target)
        .load();
  }

  State verifiedState() throws Exception {
    State state = JSON.readValue(directory.resolve("upgrade-state.json").toFile(), State.class);
    if (!runId.equals(state.runId())
        || !clone.equals(state.cloneDatabase())
        || !state.dumpSha256().equals(sha256(directory.resolve("original-full.sql"))))
      throw new IllegalStateException();
    return state;
  }

  Snapshot snapshot(Connection connection, Snapshot template) throws Exception {
    connection.setAutoCommit(false);
    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    Map<String, TableImage> tables = new TreeMap<>();
    Set<String> names = new TreeSet<>();
    if (template != null) names.addAll(template.tables().keySet());
    else
      try (ResultSet rows =
          connection
              .getMetaData()
              .getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
        while (rows.next()) names.add(rows.getString("TABLE_NAME"));
      }
    for (String table : names) {
      identifier(table);
      TableImage previous = template == null ? null : template.tables().get(table);
      List<String> columns = new ArrayList<>();
      List<String> keys = new ArrayList<>();
      if (previous != null) {
        columns.addAll(previous.columns());
        keys.addAll(previous.keys());
      } else {
        try (ResultSet rows =
            connection.getMetaData().getColumns(connection.getCatalog(), null, table, "%")) {
          while (rows.next()) columns.add(rows.getString("COLUMN_NAME"));
        }
        Map<Integer, String> ordered = new TreeMap<>();
        try (ResultSet rows =
            connection.getMetaData().getPrimaryKeys(connection.getCatalog(), null, table)) {
          while (rows.next()) ordered.put(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME"));
        }
        keys.addAll(ordered.values());
      }
      if (columns.isEmpty() || keys.isEmpty()) throw new IllegalStateException();
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long count = 0;
      try (DataOutputStream stream =
              new DataOutputStream(
                  new DigestOutputStream(OutputStream.nullOutputStream(), digest));
          Statement sql = connection.createStatement();
          ResultSet rows =
              sql.executeQuery(
                  "SELECT "
                      + identifiers(columns)
                      + " FROM `"
                      + table
                      + "` ORDER BY "
                      + identifiers(keys))) {
        while (rows.next()) {
          count++;
          for (int column = 1; column <= columns.size(); column++) {
            byte[] bytes = rows.getBytes(column);
            stream.writeInt(bytes == null ? -1 : bytes.length);
            if (bytes != null) stream.write(bytes);
          }
        }
      }
      tables.put(
          table, new TableImage(columns, keys, count, HexFormat.of().formatHex(digest.digest())));
    }
    connection.rollback();
    connection.setAutoCommit(true);
    return new Snapshot(tables);
  }

  static void requireEqual(Snapshot left, Snapshot right, boolean businessOnly) {
    for (var entry : left.tables().entrySet()) {
      if (businessOnly && "flyway_schema_history".equals(entry.getKey())) continue;
      if (!entry.getValue().equals(right.tables().get(entry.getKey())))
        throw new IllegalStateException();
    }
  }

  Path writeClientOptions() throws Exception {
    URI uri = URI.create(url.substring("jdbc:".length()));
    Path options = directory.resolve("mysql-client.cnf");
    String text =
        "[client]\nhost="
            + quoted(uri.getHost())
            + "\nport="
            + uri.getPort()
            + "\nuser="
            + quoted(username)
            + "\npassword="
            + quoted(password)
            + "\n";
    Files.writeString(options, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    return options;
  }

  static String quoted(String value) {
    return "\""
        + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        + "\"";
  }

  static String mysqlBinary(String name) {
    return "C:/Program Files/MySQL/MySQL Server 8.0/bin/" + name;
  }

  void execute(List<String> command, Path input, String label) throws Exception {
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.directory(directory.toFile());
    if (input != null) builder.redirectInput(input.toFile());
    builder.redirectOutput(directory.resolve(label + ".stdout.log").toFile());
    builder.redirectError(directory.resolve(label + ".stderr.log").toFile());
    Process process = builder.start();
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException();
    }
    if (process.exitValue() != 0) throw new IllegalStateException();
  }

  static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
      input.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  static long scalar(Connection connection, String query) throws Exception {
    try (Statement sql = connection.createStatement();
        ResultSet rows = sql.executeQuery(query)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  static void identifier(String value) {
    if (!value.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException();
  }

  static String identifiers(List<String> values) {
    return values.stream()
        .map(
            value -> {
              identifier(value);
              return "`" + value + "`";
            })
        .reduce((a, b) -> a + "," + b)
        .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  void prepareRuntime() throws Exception {
    var spring = (Map<String, Object>) config.get("spring");
    ((Map<String, Object>) spring.get("datasource")).put("url", UpgradeSafety.cloneUrl(url, clone));
    spring.put("flyway", Map.of("enabled", true));
    var volunteer = (Map<String, Object>) config.get("volunteerflow");
    ((Map<String, Object>) volunteer.get("redis")).put("environment", clone);
    volunteer.put("acceptance", Map.of("database", clone));
    volunteer.put("maintenance", Map.of("enabled", false));
    volunteer.put("events", Map.of("consumer-auto-startup", false));
    byte[] bytes = new byte[24];
    new SecureRandom().nextBytes(bytes);
    String secret = "Ac!" + HexFormat.of().formatHex(bytes) + "Z9";
    String admin = "upgrade-" + runId;
    volunteer.put(
        "bootstrap-admin",
        Map.of(
            "username",
            admin,
            "password",
            secret,
            "real-name",
            "Upgrade Acceptance Admin",
            "student-number",
            admin,
            "contact",
            "acceptance-only"));
    try (Writer writer =
        Files.newBufferedWriter(
            directory.resolve("application.yml"), StandardOpenOption.CREATE_NEW)) {
      new Yaml().dump(config, writer);
    }
    JSON.writeValue(
        directory.resolve("credentials.json").toFile(),
        Map.of(
            "runId",
            runId,
            "baseUrl",
            "http://127.0.0.1:8082",
            "adminUsername",
            admin,
            "adminPassword",
            secret));
  }
}
