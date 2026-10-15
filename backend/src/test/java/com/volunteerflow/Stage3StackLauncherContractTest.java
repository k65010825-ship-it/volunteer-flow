package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

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

class Stage3StackLauncherContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @TempDir Path temporaryDirectory;

  @Test
  void stackAndE2eLaunchersReuseOneSecretSafeEnvironmentModule() throws IOException {
    String module = Files.readString(REPOSITORY_ROOT.resolve("deploy/stage3-env.psm1"));
    String stack = Files.readString(REPOSITORY_ROOT.resolve("deploy/run-stage3-stack.ps1"));
    String e2e = Files.readString(REPOSITORY_ROOT.resolve("deploy/run-stage3-e2e.ps1"));

    assertThat(module)
        .contains("Read-Stage3EnvironmentFile")
        .contains("Invoke-WithStage3Environment")
        .contains("JWT_SECRET must not contain whitespace")
        .contains("APP_PORT")
        .contains("finally {")
        .doesNotContain("Write-Host $value")
        .doesNotContain("Write-Output $value");
    assertThat(stack)
        .contains("[ValidateSet('Config', 'Up', 'Ps', 'Logs', 'Down')]")
        .contains("Import-Module")
        .contains("Read-Stage3EnvironmentFile")
        .contains("Invoke-WithStage3Environment")
        .contains("'config', '--quiet'")
        .doesNotContain("docker compose config");
    assertThat(e2e)
        .contains("Import-Module")
        .contains("Read-Stage3EnvironmentFile")
        .contains("Invoke-WithStage3Environment")
        .doesNotContain("function Read-ComposeEnvironmentFile");
  }

  @Test
  void sharedModuleOverridesAndRestoresCallerEnvironmentWithoutPrintingValues() throws Exception {
    Path powershell = requirePowerShell();
    Path deploy = Files.createDirectories(temporaryDirectory.resolve("module-test/deploy"));
    Files.copy(REPOSITORY_ROOT.resolve("deploy/stage3-env.psm1"), deploy.resolve("stage3-env.psm1"));
    Path envFile = writeEnvironment(deploy.resolve(".env"));
    Path capture = temporaryDirectory.resolve("module-capture.txt");
    Path driver = temporaryDirectory.resolve("module-driver.ps1");
    Files.writeString(
        driver,
        "param([string]$Module,[string]$EnvFile,[string]$Capture)\n"
            + "Import-Module $Module -Force\n"
            + "$names = Get-Stage3ManagedEnvironmentNames\n"
            + "foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, 'caller-' + $name) }\n"
            + "$values = Read-Stage3EnvironmentFile $EnvFile\n"
            + "Invoke-WithStage3Environment -Environment $values -Operation {\n"
            + "  foreach ($name in $names) { if ([Environment]::GetEnvironmentVariable($name) -cne [string]$values[$name]) { throw 'override failed' } }\n"
            + "  Add-Content -LiteralPath $Capture -Value 'operation-ok'\n"
            + "}\n"
            + "foreach ($name in $names) { if ([Environment]::GetEnvironmentVariable($name) -cne ('caller-' + $name)) { throw 'restore failed' } }\n"
            + "Add-Content -LiteralPath $Capture -Value 'restore-ok'\n");

    Process process =
        new ProcessBuilder(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                driver.toString(),
                "-Module",
                deploy.resolve("stage3-env.psm1").toString(),
                "-EnvFile",
                envFile.toString(),
                "-Capture",
                capture.toString())
            .redirectErrorStream(true)
            .start();
    boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(completed).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(Files.readAllLines(capture)).containsExactly("operation-ok", "restore-ok");
    assertThat(output).doesNotContain("file$#password", "File $Admin #1", jwtSecret());
  }

  @Test
  void sharedModuleRejectsJwtWhitespaceAndInlineComments() throws Exception {
    Path powershell = requirePowerShell();
    Path deploy = Files.createDirectories(temporaryDirectory.resolve("parser-test/deploy"));
    Path module = deploy.resolve("stage3-env.psm1");
    Files.copy(REPOSITORY_ROOT.resolve("deploy/stage3-env.psm1"), module);
    Path driver = temporaryDirectory.resolve("parser-driver.ps1");
    Files.writeString(
        driver,
        "param([string]$Module,[string]$EnvFile)\n"
            + "Import-Module $Module -Force\n"
            + "Read-Stage3EnvironmentFile $EnvFile > $null\n");

    Path whitespace = writeEnvironment(deploy.resolve("whitespace.env"));
    String whitespaceContent = Files.readString(whitespace);
    Files.writeString(
        whitespace,
        whitespaceContent.replace(
            "JWT_SECRET='" + jwtSecret() + "'", "JWT_SECRET='" + jwtSecret() + " '"));
    Process whitespaceProcess = parserProcess(powershell, driver, module, whitespace);
    String whitespaceOutput = waitFor(whitespaceProcess);
    assertThat(whitespaceProcess.exitValue()).as(whitespaceOutput).isNotZero();
    assertThat(whitespaceOutput)
        .contains("JWT_SECRET must not contain whitespace")
        .doesNotContain(jwtSecret());

    Path inlineComment = writeEnvironment(deploy.resolve("inline-comment.env"));
    String inlineContent = Files.readString(inlineComment);
    Files.writeString(
        inlineComment, inlineContent.replace("APP_PORT=18088", "APP_PORT=18088 # unsupported"));
    Process inlineProcess = parserProcess(powershell, driver, module, inlineComment);
    String inlineOutput = waitFor(inlineProcess);
    assertThat(inlineProcess.exitValue()).as(inlineOutput).isNotZero();
    assertThat(inlineOutput)
        .contains("Unquoted environment value for APP_PORT contains unsupported characters")
        .doesNotContain("file$#password", jwtSecret());
  }

  @Test
  void stackLauncherOverridesConflictsAndPropagatesDockerExitCode() throws Exception {
    Path powershell = requirePowerShell();
    Path root = Files.createDirectories(temporaryDirectory.resolve("stack-test"));
    Path deploy = Files.createDirectories(root.resolve("deploy"));
    Files.copy(REPOSITORY_ROOT.resolve("deploy/stage3-env.psm1"), deploy.resolve("stage3-env.psm1"));
    Files.copy(
        REPOSITORY_ROOT.resolve("deploy/run-stage3-stack.ps1"),
        deploy.resolve("run-stage3-stack.ps1"));
    Files.writeString(deploy.resolve("docker-compose.yml"), "services: {}\n");
    Path envFile = writeEnvironment(deploy.resolve(".env"));
    Path capture = temporaryDirectory.resolve("docker-capture.txt");
    Path dockerStub = temporaryDirectory.resolve("docker-stub.ps1");
    Files.writeString(
        dockerStub,
        "param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Remaining)\n"
            + "if ($env:DB_PASSWORD -cne 'file$#password') { exit 91 }\n"
            + "if ($env:APP_PORT -cne '18088') { exit 92 }\n"
            + "Add-Content -LiteralPath $env:S3_STACK_CAPTURE -Value ($Remaining -join ' ')\n"
            + "exit [int]$env:S3_DOCKER_EXIT\n");
    Path commands = Files.createDirectories(temporaryDirectory.resolve("commands"));
    Files.writeString(
        commands.resolve("docker.cmd"),
        "@echo off\r\npowershell.exe -NoLogo -NoProfile -File \"%S3_DOCKER_STUB%\" %*\r\nexit /b %errorlevel%\r\n");

    Process success = stackProcess(powershell, deploy, envFile, commands, capture, dockerStub, "0");
    String successOutput = waitFor(success);
    assertThat(success.exitValue()).as(successOutput).isZero();
    assertThat(Files.readAllLines(capture).get(0)).contains("compose", "config", "--quiet");
    assertThat(successOutput).doesNotContain("file$#password", jwtSecret());

    Process failure = stackProcess(powershell, deploy, envFile, commands, capture, dockerStub, "23");
    String failureOutput = waitFor(failure);
    assertThat(failure.exitValue()).as(failureOutput).isEqualTo(23);
    assertThat(failureOutput).doesNotContain("file$#password", jwtSecret());
  }

  @Test
  void stackLauncherPropagatesDockerExitCodeAndRestoresPwshNativeErrorPreference()
      throws Exception {
    Path powershell = requirePowerShell7();
    Path root = Files.createDirectories(temporaryDirectory.resolve("pwsh7-stack-test"));
    Path deploy = Files.createDirectories(root.resolve("deploy"));
    Files.copy(
        REPOSITORY_ROOT.resolve("deploy/stage3-env.psm1"), deploy.resolve("stage3-env.psm1"));
    Files.copy(
        REPOSITORY_ROOT.resolve("deploy/run-stage3-stack.ps1"),
        deploy.resolve("run-stage3-stack.ps1"));
    Files.writeString(deploy.resolve("docker-compose.yml"), "services: {}\n");
    Path envFile = writeEnvironment(deploy.resolve(".env"));
    Path capture = temporaryDirectory.resolve("pwsh7-docker-capture.txt");
    Path dockerStub = temporaryDirectory.resolve("pwsh7-docker-stub.ps1");
    Files.writeString(
        dockerStub,
        "param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Remaining)\n"
            + "Add-Content -LiteralPath $env:S3_STACK_CAPTURE -Value ($Remaining -join ' ')\n"
            + "exit 23\n");
    Path commands = Files.createDirectories(temporaryDirectory.resolve("pwsh7-commands"));
    Files.writeString(
        commands.resolve("docker.cmd"),
        "@echo off\r\n"
            + "pwsh.exe -NoLogo -NoProfile -File \"%S3_DOCKER_STUB%\" %*\r\n"
            + "exit /b %errorlevel%\r\n");
    Path driver = temporaryDirectory.resolve("pwsh7-stack-driver.ps1");
    Files.writeString(
        driver,
        "param([string]$Stack,[string]$EnvFile)\n"
            + "$PSNativeCommandUseErrorActionPreference = $true\n"
            + "& $Stack -Action Config -ComposeEnvFile $EnvFile\n"
            + "$stackExitCode = $LASTEXITCODE\n"
            + "if ($PSNativeCommandUseErrorActionPreference -ne $true) { exit 97 }\n"
            + "exit $stackExitCode\n");

    ProcessBuilder builder =
        new ProcessBuilder(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                driver.toString(),
                "-Stack",
                deploy.resolve("run-stage3-stack.ps1").toString(),
                "-EnvFile",
                envFile.toString())
            .redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    environment.put("PATH", commands + ";" + environment.get("PATH"));
    environment.put("S3_DOCKER_STUB", dockerStub.toString());
    environment.put("S3_STACK_CAPTURE", capture.toString());

    Process process = builder.start();
    String output = waitFor(process);

    assertThat(process.exitValue()).as(output).isEqualTo(23);
    assertThat(Files.readAllLines(capture))
        .singleElement()
        .asString()
        .contains("compose", "config", "--quiet");
    assertThat(output).doesNotContain("file$#password", jwtSecret());
  }

  private Process stackProcess(
      Path powershell,
      Path deploy,
      Path envFile,
      Path commands,
      Path capture,
      Path dockerStub,
      String dockerExit) {
    ProcessBuilder builder =
        new ProcessBuilder(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                deploy.resolve("run-stage3-stack.ps1").toString(),
                "-Action",
                "Config",
                "-ComposeEnvFile",
                envFile.toString())
            .redirectErrorStream(true);
    Map<String, String> environment = builder.environment();
    environment.put("PATH", commands + ";" + environment.get("PATH"));
    environment.put("S3_DOCKER_STUB", dockerStub.toString());
    environment.put("S3_DOCKER_EXIT", dockerExit);
    environment.put("S3_STACK_CAPTURE", capture.toString());
    for (String name : managedNames()) environment.put(name, "caller-conflict-" + name);
    try {
      return builder.start();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String waitFor(Process process) throws Exception {
    boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(completed).as(output).isTrue();
    return output;
  }

  private Process parserProcess(Path powershell, Path driver, Path module, Path environmentFile)
      throws IOException {
    return new ProcessBuilder(
            powershell.toString(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            driver.toString(),
            "-Module",
            module.toString(),
            "-EnvFile",
            environmentFile.toString())
        .redirectErrorStream(true)
        .start();
  }

  private Path writeEnvironment(Path target) throws IOException {
    Files.writeString(
        target,
        "# full-line comments are supported\n"
            + "DB_HOST='file-db.internal'\n"
            + "DB_PORT='3307'\n"
            + "DB_NAME='volunteer_flow'\n"
            + "DB_USERNAME='file-user'\n"
            + "DB_PASSWORD='file$#password'\n"
            + "JWT_SECRET='" + jwtSecret() + "'\n"
            + "BOOTSTRAP_ADMIN_USERNAME='file-bootstrap'\n"
            + "BOOTSTRAP_ADMIN_PASSWORD='File$#Bootstrap1'\n"
            + "BOOTSTRAP_ADMIN_REAL_NAME='File $Admin #1'\n"
            + "BOOTSTRAP_ADMIN_STUDENT_NUMBER='file-001'\n"
            + "BOOTSTRAP_ADMIN_CONTACT='ops$#@example.invalid'\n"
            + "APP_PORT=18088\n");
    return target;
  }

  private static List<String> managedNames() {
    return List.of(
        "DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "JWT_SECRET",
        "BOOTSTRAP_ADMIN_USERNAME", "BOOTSTRAP_ADMIN_PASSWORD", "BOOTSTRAP_ADMIN_REAL_NAME",
        "BOOTSTRAP_ADMIN_STUDENT_NUMBER", "BOOTSTRAP_ADMIN_CONTACT", "APP_PORT");
  }

  private static String jwtSecret() {
    return Base64.getEncoder()
        .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
  }

  private static Path requirePowerShell() {
    Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
    return List.of(
            Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe"),
            Path.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe"))
        .stream()
        .filter(Files::isRegularFile)
        .findFirst()
        .orElseThrow();
  }

  private static Path requirePowerShell7() {
    Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
    return List.of(
            Path.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe"),
            Path.of(
                System.getProperty("user.home"),
                ".cache",
                "codex-runtimes",
                "codex-primary-runtime",
                "dependencies",
                "native",
                "powershell",
                "pwsh.exe"))
        .stream()
        .filter(Files::isRegularFile)
        .findFirst()
        .orElseGet(
            () -> {
              Assumptions.assumeTrue(false, "PowerShell 7 is required");
              throw new IllegalStateException("unreachable");
            });
  }

  private static Path locateRepositoryRoot() {
    Path workingDirectory = Path.of(System.getProperty("user.dir"));
    if (Files.isRegularFile(workingDirectory.resolve("backend/pom.xml"))) return workingDirectory;
    if (Files.isRegularFile(workingDirectory.resolve("pom.xml"))) return workingDirectory.getParent();
    throw new IllegalStateException("Cannot locate repository root from " + workingDirectory);
  }
}
