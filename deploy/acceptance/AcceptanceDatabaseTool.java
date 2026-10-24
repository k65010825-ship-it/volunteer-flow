import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.sql.*;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Explicitly creates ONE absent UUID-named acceptance database; never alters the source database.
 */
public final class AcceptanceDatabaseTool {
  static boolean validDatabase(String name) {
    return name != null && name.matches("vf_acceptance_[0-9a-f]{32}");
  }

  static String isolatedUrl(String original, String name) {
    if (!validDatabase(name)
        || original == null
        || !original.matches("jdbc:mysql://[^/]+/volunteer_flow(?:\\?.*)?")) return null;
    return original.replaceFirst("/volunteer_flow(?=\\?|$)", "/" + name);
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) {
    String stage = "ARGUMENTS";
    try {
      if (args.length != 3) throw new IllegalArgumentException();
      String runId = args[2];
      UUID id = UUID.fromString(runId);
      if (!id.toString().equals(runId) || id.version() != 4) throw new IllegalArgumentException();
      String database = "vf_acceptance_" + runId.replace("-", "");
      Path output = Path.of(args[1]).toRealPath();
      Path runtime = output.resolve("application.yml");
      Path credentials = output.resolve("credentials.json");
      if (!Files.isDirectory(output) || Files.exists(runtime) || Files.exists(credentials))
        throw new IllegalArgumentException();
      stage = "READ_CONFIGURATION";
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      Map<String, Object> config;
      try (InputStream input = Files.newInputStream(Path.of(args[0]))) {
        config = new Yaml(new SafeConstructor(options)).load(input);
      }
      Map<String, Object> spring = (Map<String, Object>) config.get("spring");
      Map<String, Object> datasource = (Map<String, Object>) spring.get("datasource");
      String source = (String) datasource.get("url");
      String isolated = isolatedUrl(source, database);
      if (isolated == null) throw new IllegalArgumentException();
      stage = "CONNECT_SOURCE";
      if (datasource.get("username") == null || datasource.get("password") == null)
        throw new IllegalArgumentException();
      String username = String.valueOf(datasource.get("username"));
      String password = String.valueOf(datasource.get("password"));
      try (Connection connection = DriverManager.getConnection(source, username, password)) {
        try (PreparedStatement query =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
          query.setString(1, database);
          try (ResultSet rows = query.executeQuery()) {
            rows.next();
            if (rows.getLong(1) != 0) throw new IllegalStateException();
          }
        }
        stage = "CREATE_ISOLATED_DATABASE";
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate(
              "CREATE DATABASE `"
                  + database
                  + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
      }
      stage = "PREPARE_RUNTIME";
      datasource.put("url", isolated);
      spring.put("flyway", Map.of("enabled", true));
      spring.put("profiles", Map.of("active", "acceptance"));
      Map<String, Object> volunteer = (Map<String, Object>) config.get("volunteerflow");
      ((Map<String, Object>) volunteer.get("redis")).put("environment", database);
      byte[] random = new byte[24];
      new SecureRandom().nextBytes(random);
      String adminPassword = "Ac!" + HexFormat.of().formatHex(random) + "Z9";
      String adminUsername = "livee2e-" + runId + "-admin";
      volunteer.put(
          "bootstrap-admin",
          Map.of(
              "username",
              adminUsername,
              "password",
              adminPassword,
              "real-name",
              "Isolated Acceptance Admin",
              "student-number",
              adminUsername,
              "contact",
              "acceptance-only"));
      volunteer.put("maintenance", Map.of("enabled", false));
      volunteer.put("events", Map.of("consumer-auto-startup", false));
      volunteer.put("acceptance", Map.of("database", database));
      config.put("server", Map.of("port", 8082, "address", "127.0.0.1"));
      // Directory ACL must be restricted before invoking this tool. Never print either secret file.
      stage = "WRITE_RESTRICTED_FILES";
      try (Writer writer = Files.newBufferedWriter(runtime, StandardOpenOption.CREATE_NEW)) {
        new Yaml().dump(config, writer);
      }
      Files.writeString(
          credentials,
          "{\"runId\":\""
              + runId
              + "\",\"baseUrl\":\"http://127.0.0.1:8082\",\"adminUsername\":\""
              + adminUsername
              + "\",\"adminPassword\":\""
              + adminPassword
              + "\"}",
          StandardOpenOption.CREATE_NEW);
      System.out.println("ACCEPTANCE_DATABASE_CREATED=" + database);
      System.out.println("ACCEPTANCE_RUNTIME_PREPARED=true");
    } catch (Exception failure) {
      // Never serialize SQL/config/exception messages containing credentials.
      System.out.println("ACCEPTANCE_PREPARATION_FAILED=" + stage);
      if (failure instanceof SQLException sql)
        System.out.println(
            "ACCEPTANCE_SQL_STATE=" + sql.getSQLState() + ";VENDOR_CODE=" + sql.getErrorCode());
      System.exit(2);
    }
  }
}
