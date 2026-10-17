package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Stage4dEnvironmentPreparationContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();
  private static final Path SCRIPT =
      REPOSITORY_ROOT.resolve("deploy/stage4d/prepare-stage4d-env.ps1");

  @TempDir Path temporaryDirectory;

  @Test
  void convertsExistingLocalYamlWithoutPrintingSecrets() throws Exception {
    String jwt =
        Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    String password = "space$#password";
    Path source = temporaryDirectory.resolve("application-local.yml");
    Path target = temporaryDirectory.resolve("stage4d.env");
    Files.writeString(
        source,
        "volunteerflow:\n"
            + "  auth:\n"
            + "    jwt:\n"
            + "      secret: '"
            + jwt
            + "'\n"
            + "spring:\n"
            + "  data:\n"
            + "    redis:\n"
            + "      host: 192.0.2.20\n"
            + "      port: 6379\n"
            + "      database: 2\n"
            + "      connect-timeout: 1s\n"
            + "      timeout: 2s\n"
            + "  datasource:\n"
            + "    url: jdbc:mysql://192.0.2.10:3307/volunteer_flow?serverTimezone=Asia/Shanghai\n"
            + "    username: vf_user\n"
            + "    password: '"
            + password
            + "'\n");

    Process process =
        new ProcessBuilder(
                requirePowerShell().toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                SCRIPT.toString(),
                "-ApplicationLocalFile",
                source.toString(),
                "-OutputFile",
                target.toString())
            .redirectErrorStream(true)
            .start();
    boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(completed).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(output).doesNotContain(password).doesNotContain(jwt);
    assertThat(Files.readString(target))
        .contains("DB_HOST='192.0.2.10'")
        .contains("DB_PORT='3307'")
        .contains("DB_NAME='volunteer_flow'")
        .contains("DB_USERNAME='vf_user'")
        .contains("DB_PASSWORD='space$#password'")
        .contains("JWT_SECRET='" + jwt + "'")
        .contains("SPRING_FLYWAY_ENABLED=false")
        .contains("REDIS_HOST='192.0.2.20'")
        .contains("REDIS_PORT='6379'")
        .contains("RATE_LIMIT_KEY_SECRET='runner-replaces-this-value'")
        .doesNotContain("replace-with-mysql-host");
  }

  @Test
  void sourceRequiresExactKnownKeysAndNeverEchoesValues() throws IOException {
    String script = Files.readString(SCRIPT);
    assertThat(script)
        .contains("spring.datasource.url")
        .contains("spring.datasource.username")
        .contains("spring.datasource.password")
        .contains("spring.data.redis.host")
        .contains("volunteerflow.auth.jwt.secret")
        .contains("SPRING_FLYWAY_ENABLED=false")
        .contains("runner-replaces-this-value")
        .doesNotContain("Write-Host $value")
        .doesNotContain("Write-Output $value");
  }

  private static Path requirePowerShell() {
    Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
    return List.of(
            Path.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe"),
            Path.of(
                System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe"))
        .stream()
        .filter(Files::isRegularFile)
        .findFirst()
        .orElseThrow();
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
