package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Stage3E2eFixtureContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @TempDir Path temporaryDirectory;

  @Test
  void acceptsOnlyCanonicalUuidRunIds() {
    assertThat(Stage3E2eFixtureIT.validatedRunId("6f96e55b-4d96-4d1d-9c6e-95d92df6e444"))
        .isEqualTo("6f96e55b-4d96-4d1d-9c6e-95d92df6e444");

    assertThatThrownBy(() -> Stage3E2eFixtureIT.validatedRunId(" "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Stage3E2eFixtureIT.validatedRunId("not-a-uuid"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> Stage3E2eFixtureIT.validatedRunId("6F96E55B-4D96-4D1D-9C6E-95D92DF6E444"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void wrapperAlwaysCleansUpAndRestoresCallerState() throws IOException {
    String wrapper = Files.readString(REPOSITORY_ROOT.resolve("deploy/run-stage3-e2e.ps1"));

    assertThat(wrapper)
        .contains("[Guid]::NewGuid().ToString('D').ToLowerInvariant()")
        .contains("Import-Module")
        .contains("Read-Stage3EnvironmentFile")
        .contains("Invoke-WithStage3Environment")
        .contains("-Dstage3.e2e.action=$fixtureActionValue")
        .contains("Invoke-Fixture 'seed'")
        .contains("Invoke-Fixture 'cleanup'")
        .contains("PLAYWRIGHT_BASE_URL")
        .contains("S3_E2E_RUN_ID")
        .contains("try {")
        .contains("finally {")
        .contains("Set-Location -LiteralPath $startLocation")
        .doesNotContain("BackendConfigPath")
        .doesNotContain("SPRING_CONFIG_ADDITIONAL_LOCATION")
        .doesNotContain("Write-Host $env:")
        .doesNotContain("Write-Output $env:");
  }

  @Test
  void wrapperPassesLiteralSeedAndCleanupActionsToSeparateFixtureProcesses() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
    Path powershell = locatePowerShell();
    Assumptions.assumeTrue(powershell != null, "PowerShell is required for the wrapper regression");

    Path deploy = Files.createDirectories(temporaryDirectory.resolve("deploy"));
    Path backend = Files.createDirectories(temporaryDirectory.resolve("backend"));
    Files.createDirectories(temporaryDirectory.resolve("frontend"));
    String jwtSecret =
        Base64.getEncoder()
            .encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    Files.writeString(
        deploy.resolve(".env"),
        "DB_HOST='fixture-db.internal'\n"
            + "DB_PORT='3307'\n"
            + "DB_NAME='volunteer_flow'\n"
            + "DB_USERNAME='fixture-user'\n"
            + "DB_PASSWORD='fixture$#password'\n"
            + "JWT_SECRET='"
            + jwtSecret
            + "'\n"
            + "SPRING_FLYWAY_ENABLED=false\n"
            + "BOOTSTRAP_ADMIN_USERNAME='fixture-bootstrap'\n"
            + "BOOTSTRAP_ADMIN_PASSWORD='Fixture$#Bootstrap1'\n"
            + "BOOTSTRAP_ADMIN_REAL_NAME='Fixture $Admin #1'\n"
            + "BOOTSTRAP_ADMIN_STUDENT_NUMBER='fixture-001'\n"
            + "BOOTSTRAP_ADMIN_CONTACT='ops$#@example.invalid'\n"
            + "APP_PORT=8088\n");
    String wrapper =
        Files.readString(REPOSITORY_ROOT.resolve("deploy/run-stage3-e2e.ps1"))
            .replace("& .\\mvnw.cmd `", "& $env:S3_E2E_MAVEN_STUB `");
    Files.writeString(deploy.resolve("run-stage3-e2e.ps1"), wrapper);
    Files.copy(
        REPOSITORY_ROOT.resolve("deploy/stage3-env.psm1"), deploy.resolve("stage3-env.psm1"));

    Path capture = temporaryDirectory.resolve("fixture-actions.txt");
    Path mavenStub = temporaryDirectory.resolve("maven-stub.ps1");
    Files.writeString(
        mavenStub,
        "param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Remaining)\n"
            + "$required = @('DB_HOST','DB_PORT','DB_NAME','DB_USERNAME','DB_PASSWORD','JWT_SECRET',"
            + "'SPRING_FLYWAY_ENABLED',"
            + "'BOOTSTRAP_ADMIN_USERNAME','BOOTSTRAP_ADMIN_PASSWORD','BOOTSTRAP_ADMIN_REAL_NAME',"
            + "'BOOTSTRAP_ADMIN_STUDENT_NUMBER','BOOTSTRAP_ADMIN_CONTACT')\n"
            + "foreach ($name in $required) { if ($null -eq [Environment]::GetEnvironmentVariable($name)) { exit 91 } }\n"
            + "if ($env:DB_PASSWORD -cne 'fixture$#password') { exit 92 }\n"
            + "if ($env:SPRING_FLYWAY_ENABLED -cne 'false') { exit 96 }\n"
            + "if ($env:BOOTSTRAP_ADMIN_PASSWORD -cne 'Fixture$#Bootstrap1') { exit 95 }\n"
            + "if ($env:BOOTSTRAP_ADMIN_REAL_NAME -cne 'Fixture $Admin #1') { exit 93 }\n"
            + "if ($env:BOOTSTRAP_ADMIN_CONTACT -cne 'ops$#@example.invalid') { exit 94 }\n"
            + "$action = ($Remaining | Where-Object { $_ -like '-Dstage3.e2e.action=*' }) -join ''\n"
            + "Add-Content -LiteralPath $env:S3_E2E_CAPTURE -Value \"$action|environment-ok\"\n"
            + "& cmd.exe /c exit 0\n");
    Path commandDirectory = Files.createDirectories(temporaryDirectory.resolve("commands"));
    Files.writeString(commandDirectory.resolve("npx.cmd"), "@echo off\r\nexit /b 0\r\n");

    ProcessBuilder processBuilder =
        new ProcessBuilder(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                deploy.resolve("run-stage3-e2e.ps1").toString())
            .redirectErrorStream(true);
    Map<String, String> environment = processBuilder.environment();
    environment.put("S3_E2E_MAVEN_STUB", mavenStub.toString());
    environment.put("S3_E2E_CAPTURE", capture.toString());
    environment.put("PATH", commandDirectory + ";" + environment.get("PATH"));
    for (String name :
        List.of(
            "DB_HOST",
            "DB_PORT",
            "DB_NAME",
            "DB_USERNAME",
            "DB_PASSWORD",
            "JWT_SECRET",
            "SPRING_FLYWAY_ENABLED",
            "BOOTSTRAP_ADMIN_USERNAME",
            "BOOTSTRAP_ADMIN_PASSWORD",
            "BOOTSTRAP_ADMIN_REAL_NAME",
            "BOOTSTRAP_ADMIN_STUDENT_NUMBER",
            "BOOTSTRAP_ADMIN_CONTACT",
            "APP_PORT")) {
      environment.put(name, "caller-conflict-" + name);
    }

    Process process = processBuilder.start();
    boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    String output = new String(process.getInputStream().readAllBytes());

    assertThat(completed).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(capture).as(output).exists();
    assertThat(Files.readAllLines(capture))
        .containsExactlyInAnyOrder(
            "-Dstage3.e2e.action=seed|environment-ok",
            "-Dstage3.e2e.action=cleanup|environment-ok");
    assertThat(output)
        .doesNotContain("fixture$#password")
        .doesNotContain("Fixture$#Bootstrap1")
        .doesNotContain("Fixture $Admin #1")
        .doesNotContain("ops$#@example.invalid")
        .doesNotContain(jwtSecret);
  }

  @Test
  void fixtureDowngradesBrowserAdministratorAfterCreatingOwnedOrganization() throws IOException {
    String fixture =
        Files.readString(
            REPOSITORY_ROOT.resolve(
                "backend/src/test/java/com/volunteerflow/Stage3E2eFixtureIT.java"));

    int organizationCreated = fixture.indexOf("organizations.create(");
    int browserRoleDowngraded = fixture.indexOf("administrator.setPlatformRole(\"USER\")");
    int browserRolePersisted = fixture.indexOf("users.updateById(administrator)");

    assertThat(organizationCreated).isGreaterThanOrEqualTo(0);
    assertThat(browserRoleDowngraded).isGreaterThan(organizationCreated);
    assertThat(browserRolePersisted).isGreaterThan(browserRoleDowngraded);
  }

  @Test
  void fixtureLetsTheWrapperControlFlywayWithoutDroppingTestOnlyOverrides() throws IOException {
    String fixture =
        Files.readString(
            REPOSITORY_ROOT.resolve(
                "backend/src/test/java/com/volunteerflow/Stage3E2eFixtureIT.java"));

    assertThat(fixture)
        .contains("\"volunteerflow.bootstrap-admin.username=\"")
        .contains("@MockitoBean private ActivityFinalizationJob automaticActivityFinalizer;")
        .contains("@MockitoBean private PromotionExpiryJob automaticPromotionExpiry;")
        .doesNotContain("spring.flyway.enabled");
  }

  @Test
  void browserSpecUsesPublicNginxWithoutMocksOrSecretArtifacts() throws IOException {
    String config = Files.readString(REPOSITORY_ROOT.resolve("frontend/playwright.config.ts"));
    String spec = Files.readString(REPOSITORY_ROOT.resolve("frontend/e2e/stage3.spec.ts"));

    assertThat(config)
        .contains("process.env.PLAYWRIGHT_BASE_URL")
        .contains("retries: 0")
        .contains("screenshot: \"only-on-failure\"")
        .doesNotContain("webServer");
    assertThat(spec)
        .contains("process.env.S3_E2E_RUN_ID")
        .contains("redirects a protected notification deep link to login through Nginx")
        .contains("test.use({ trace: \"off\", screenshot: \"off\" })")
        .contains("/notifications")
        .contains("/check-in-management")
        .contains("Backend logs contained plaintext check-in code")
        .contains("page.on(\"pageerror\"")
        .contains("message.type() === \"error\"")
        .contains("context.on(\"page\"")
        .contains("Browser emitted page or console errors")
        .doesNotContain("page.route(")
        .doesNotContain("context.route(")
        .doesNotContain("console.log")
        .doesNotContain("message.args()")
        .doesNotContain("message.text()");

    int scenario = spec.indexOf("completes Stage 3 notifications");
    int lastCodeUsingAction = spec.indexOf("你没有执行此操作的权限", scenario);
    int finalLogAssertion = spec.lastIndexOf("assertCodeAbsentFromBackendLogs(code)");
    int scenarioFinally = spec.indexOf("  } finally {", scenario);
    assertThat(finalLogAssertion).isGreaterThan(lastCodeUsingAction);
    assertThat(finalLogAssertion).isLessThan(scenarioFinally);
    assertThat(countOccurrences(spec, "assertCodeAbsentFromBackendLogs(code)")).isEqualTo(1);
  }

  @Test
  void vitestExcludesTheRealBrowserSpec() throws IOException {
    String packageJson = Files.readString(REPOSITORY_ROOT.resolve("frontend/package.json"));

    assertThat(packageJson).contains("vitest run --exclude e2e/**");
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

  private static Path locatePowerShell() {
    List<Path> candidates =
        List.of(
            Path.of(
                System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32",
                "WindowsPowerShell",
                "v1.0",
                "powershell.exe"),
            Path.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe"));
    return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
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
}
