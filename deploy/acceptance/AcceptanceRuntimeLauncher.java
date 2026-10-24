import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Starts only the UUID-database acceptance profile, with inherited application overrides removed.
 */
public final class AcceptanceRuntimeLauncher {
  @SuppressWarnings("unchecked")
  public static void main(String[] args) {
    try {
      if (args.length != 2) throw new IllegalArgumentException();
      Path jar = Path.of(args[0]).toRealPath();
      Path directory = Path.of(args[1]).toRealPath();
      Path runtime = directory.resolve("application.yml");
      if (Files.exists(directory.resolve("backend.pid"))) throw new IllegalArgumentException();
      Map<String, Object> config;
      try (InputStream input = Files.newInputStream(runtime)) {
        config = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
      }
      Map<String, Object> spring = (Map<String, Object>) config.get("spring");
      Map<String, Object> volunteer = (Map<String, Object>) config.get("volunteerflow");
      String database = String.valueOf(((Map<?, ?>) volunteer.get("acceptance")).get("database"));
      String url = String.valueOf(((Map<?, ?>) spring.get("datasource")).get("url"));
      if (!database.matches("vf_acceptance_[0-9a-f]{32}")
          || !url.matches("jdbc:mysql://[^/]+/" + database + "(?:\\?.*)?")
          || !Boolean.FALSE.equals(((Map<?, ?>) volunteer.get("maintenance")).get("enabled"))
          || !Boolean.FALSE.equals(
              ((Map<?, ?>) volunteer.get("events")).get("consumer-auto-startup")))
        throw new IllegalArgumentException();
      SecureRandom random = new SecureRandom();
      byte[] bytes = new byte[48];
      random.nextBytes(bytes);
      Map<String, Object> rate = new LinkedHashMap<>();
      if (volunteer.get("rate-limit") instanceof Map<?, ?> existing)
        existing.forEach((key, value) -> rate.put(String.valueOf(key), value));
      rate.put("key-secret", Base64.getEncoder().encodeToString(bytes));
      volunteer.put("rate-limit", rate);
      // Do not share production JWT trust with an isolated database.
      Map<String, Object> auth = (Map<String, Object>) volunteer.get("auth");
      Map<String, Object> jwt = (Map<String, Object>) auth.get("jwt");
      random.nextBytes(bytes);
      jwt.put("secret", Base64.getEncoder().encodeToString(bytes));
      try (Writer writer = Files.newBufferedWriter(runtime)) {
        new Yaml().dump(config, writer);
      }
      String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
      // Run a private copy so the normal target directory remains available for clean tests.
      Path runtimeJar = directory.resolve("acceptance-backend.jar");
      Files.copy(jar, runtimeJar);
      List<String> command =
          List.of(
              java,
              "-jar",
              runtimeJar.toString(),
              "--spring.config.additional-location=" + runtime.toUri(),
              "--spring.profiles.active=acceptance",
              "--spring.flyway.enabled=true",
              "--server.port=8082",
              "--server.address=127.0.0.1",
              "--volunteerflow.maintenance.enabled=false",
              "--volunteerflow.events.consumer-auto-startup=false");
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.directory(directory.toFile());
      builder
          .environment()
          .keySet()
          .removeIf(
              key ->
                  key.matches(
                      "(?i)(SPRING_.*|VOLUNTEERFLOW_.*|SERVER_.*|DB_.*|REDIS_.*|JWT_.*|BOOTSTRAP_ADMIN_.*|RATE_LIMIT_.*|KAFKA_.*|OUTBOX_.*|PROMOTION_.*|ACTIVITY_.*|DYNAMIC_.*|APP_ENV|AUTH_SECURE_COOKIE)"));
      builder.redirectOutput(directory.resolve("backend.stdout.log").toFile());
      builder.redirectError(directory.resolve("backend.stderr.log").toFile());
      Process process = builder.start();
      Files.writeString(
          directory.resolve("backend.pid"),
          Long.toString(process.pid()),
          StandardOpenOption.CREATE_NEW);
      System.out.println("ACCEPTANCE_BACKEND_PID=" + process.pid());
      System.out.println("ACCEPTANCE_BACKEND_PORT=8082");
      System.out.println("ACCEPTANCE_DATABASE=" + database);
    } catch (Exception failure) {
      System.out.println("ACCEPTANCE_LAUNCH_FAILED=true");
      System.exit(2);
    }
  }
}
