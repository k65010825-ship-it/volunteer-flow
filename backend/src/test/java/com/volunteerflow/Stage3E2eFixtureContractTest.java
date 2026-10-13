package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
        .contains("-Dstage3.e2e.action=$fixtureActionValue")
        .contains("Invoke-Fixture 'seed'")
        .contains("Invoke-Fixture 'cleanup'")
        .contains("PLAYWRIGHT_BASE_URL")
        .contains("S3_E2E_RUN_ID")
        .contains("try {")
        .contains("finally {")
        .contains("Set-Location -LiteralPath $startLocation")
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
    Files.createDirectories(backend.resolve("config"));
    Files.writeString(backend.resolve("config/application-local.yml"), "# stub\n");
    Files.writeString(deploy.resolve(".env"), "# stub\n");
    String wrapper =
        Files.readString(REPOSITORY_ROOT.resolve("deploy/run-stage3-e2e.ps1"))
            .replace("& .\\mvnw.cmd `", "& $env:S3_E2E_MAVEN_STUB `");
    Files.writeString(deploy.resolve("run-stage3-e2e.ps1"), wrapper);

    Path capture = temporaryDirectory.resolve("fixture-actions.txt");
    Path mavenStub = temporaryDirectory.resolve("maven-stub.ps1");
    Files.writeString(
        mavenStub,
        "param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Remaining)\n"
            + "Add-Content -LiteralPath $env:S3_E2E_CAPTURE -Value ($Remaining -join ' ')\n"
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

    Process process = processBuilder.start();
    boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    String output = new String(process.getInputStream().readAllBytes());

    assertThat(completed).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(capture).as(output).exists();
    assertThat(Files.readAllLines(capture))
        .anySatisfy(line -> assertThat(line).contains("-Dstage3.e2e.action=seed"))
        .anySatisfy(line -> assertThat(line).contains("-Dstage3.e2e.action=cleanup"));
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
}
