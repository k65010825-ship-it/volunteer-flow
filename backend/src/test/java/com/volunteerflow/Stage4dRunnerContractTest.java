package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Stage4dRunnerContractTest {

  private static final String RUN_ID = "6f96e55b-4d96-4d1d-9c6e-95d92df6e444";
  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();
  private static final Path DEPLOY = REPOSITORY_ROOT.resolve("deploy/stage4d");

  @TempDir Path temporaryDirectory;

  @Test
  void localRunnerUsesCommittedArchiveExplicitIdentityAndRunScopedRemoteRoot()
      throws IOException {
    String runner = read("run-stage4d.ps1");

    assertThat(runner)
        .contains("[Guid]::TryParseExact($RunId, 'D'")
        .contains("@(Get-Command $DefaultName -CommandType Application -ErrorAction Stop)[0].Source")
        .contains("ToLowerInvariant()")
        .contains("feat/stage-4d")
        .contains("status", "--porcelain", "--untracked-files=all")
        .contains("archive", "--format=tar")
        .contains("/opt/volunteerflow-stage4d/$RunId")
        .contains("vf4d$($RunId.Replace('-', ''))")
        .contains("-i", "$resolvedIdentity")
        .contains("RandomNumberGenerator")
        .contains("RATE_LIMIT_KEY_SECRET")
        .contains("[string]::Join(\"`n\", $protectedLines) + \"`n\"")
        .contains("primaryStatus")
        .contains("recoveryStatus")
        .contains("cleanupStatus")
        .contains("remoteRunDirectoryStatus")
        .contains("keyRemovalStatus")
        .contains("evidenceDownloadStatus")
        .contains("Refusing to remove unexpected remote run directory")
        .contains("rm -rf -- '$remoteRoot'")
        .doesNotContain("Write-Host $composeEnvironment")
        .doesNotContain("Write-Output $composeEnvironment");
  }

  @Test
  void remoteRunnerFailsClosedAndRestoresRedisBeforeFixtureCleanup() throws IOException {
    String remote = read("remote-stage4d.sh");
    String attributes = Files.readString(REPOSITORY_ROOT.resolve(".gitattributes"));

    assertThat(remote)
        .contains("set -Eeuo pipefail")
        .contains("docker compose version")
        .contains("export STAGE4D_FIXTURE_ACTION=snapshot")
        .contains("export STAGE4D_DATASET=normal")
        .contains("df -Pk")
        .contains("/dev/tcp/")
        .contains("config --no-interpolate")
        .contains("REDIS_HOST=127.0.0.1")
        .contains("REDIS_PORT=6379")
        .contains("--force-recreate backend-a backend-b")
        .contains("registration.js")
        .contains("rate-limit.js")
        .contains("recovery.js")
        .contains("local script=\"$1\" dataset=\"$2\"")
        .contains("local scenario=\"${script%.js}\"")
        .contains("fixture_action verify")
        .contains("restore_redis")
        .contains("cleanup_fixtures")
        .contains("if ! compose down --remove-orphans")
        .contains("run-status.json")
        .contains("compose logs")
        .contains("upstream_addr=")
        .doesNotContain("docker stop mysql")
        .doesNotContain("docker stop redis")
        .doesNotContain("docker compose down -v");
    assertThat(remote.indexOf("restore_redis")).isLessThan(remote.indexOf("cleanup_fixtures"));
    assertThat(remote.indexOf("if ! cleanup_fixtures"))
        .isLessThan(remote.indexOf("if ! compose down --remove-orphans"));
    assertThat(attributes).contains("*.sh text eol=lf").contains("backend/mvnw text eol=lf");
  }

  @Test
  void temporaryKeyScriptsUseAnExactEd25519LineAndDeleteLocalKeyLast() throws IOException {
    String bootstrap = read("bootstrap-temporary-ssh.ps1");
    String removal = read("remove-temporary-ssh.ps1");

    assertThat(bootstrap)
        .contains("ssh-keygen")
        .contains("ed25519")
        .contains("volunteerflow-stage4d-")
        .contains("Start-Process")
        .contains("RedirectStandardInput")
        .contains("BatchMode=yes")
        .doesNotContain("-Password")
        .doesNotContain("sshpass");
    assertThat(removal)
        .contains("authorized_keys")
        .contains("grep -Fvx --")
        .contains("grep -qxF --")
        .contains("BatchMode=yes")
        .contains("$scriptBody = $scriptBody.Replace(\"`r`n\", \"`n\")")
        .contains("[Convert]::ToBase64String")
        .contains("base64 -d | sh")
        .contains("Remove-Item -LiteralPath $resolvedIdentity")
        .doesNotContain("Remove-Item ~/.ssh/authorized_keys")
        .doesNotContain("rm ~/.ssh/authorized_keys");
    assertThat(removal.indexOf("grep -qxF --"))
        .isLessThan(removal.indexOf("Remove-Item -LiteralPath $resolvedIdentity"));
  }

  @Test
  void runnerValidatesMissingEnvironmentBeforeAnyRemoteMutation() throws Exception {
    Path runner = copyRunnerToTemporaryRepository();
    Path capture = temporaryDirectory.resolve("remote-capture.txt");
    Path identity = Files.writeString(temporaryDirectory.resolve("identity"), "test-key");
    Path stub = writeCaptureStub(temporaryDirectory.resolve("remote-stub.ps1"), capture, "remote");

    Process process =
        runnerProcess(
            runner,
            identity,
            temporaryDirectory.resolve("missing.env"),
            stub,
            stub,
            stub);
    String output = waitFor(process);

    assertThat(process.exitValue()).as(output).isNotZero();
    assertThat(capture).doesNotExist();
    assertThat(output).doesNotContain("test-key");
  }

  @Test
  void runnerRejectsDirtyTreeWithoutCallingSshOrScpAndWithoutLeakingEnvSecrets()
      throws Exception {
    Path runner = copyRunnerToTemporaryRepository();
    Path capture = temporaryDirectory.resolve("commands.txt");
    Path identity = Files.writeString(temporaryDirectory.resolve("identity-dirty"), "private-key");
    Files.writeString(Path.of(identity + ".pub"), "ssh-ed25519 AAAA volunteerflow-stage4d-test");
    String markerSecret = "space $ hash # quote ' marker";
    Path environment =
        Files.writeString(
            temporaryDirectory.resolve("stage4d.env"),
            "DB_HOST=db.invalid\n"
                + "DB_PORT=3307\n"
                + "DB_NAME=volunteer_flow\n"
                + "DB_USERNAME=tester\n"
                + "DB_PASSWORD='"
                + markerSecret.replace("'", "\\'")
                + "'\n"
                + "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\n"
                + "SPRING_FLYWAY_ENABLED=false\n"
                + "REDIS_HOST=redis.invalid\n"
                + "REDIS_PORT=6379\n");
    Path git = writeGitDirtyStub(temporaryDirectory.resolve("git-stub.ps1"), capture);
    Path remote = writeCaptureStub(temporaryDirectory.resolve("remote-dirty.ps1"), capture, "REMOTE");

    Process process = runnerProcess(runner, identity, environment, remote, remote, git);
    String output = waitFor(process);

    assertThat(process.exitValue()).as(output).isNotZero();
    assertThat(Files.readString(capture)).contains("git").doesNotContain("REMOTE");
    assertThat(output).doesNotContain(markerSecret).doesNotContain("private-key");
  }

  @Test
  void runnerPreservesRemoteFailureWhileReportingEvidenceRecoveryCleanupAndKeyRemoval()
      throws Exception {
    Path runner = copyRunnerToTemporaryRepository();
    Path capture = temporaryDirectory.resolve("success-path-commands.txt");
    Path identity = Files.writeString(temporaryDirectory.resolve("identity-remote"), "private-key");
    Files.writeString(
        Path.of(identity + ".pub"),
        "ssh-ed25519 AAAA volunteerflow-stage4d-6f96e55b-4d96-4d1d-9c6e-95d92df6e444");
    String markerSecret = "space $ hash # quote ' marker";
    Path environment = writeCompleteEnvironment(markerSecret);
    Path remoteEvidence = Files.createDirectories(temporaryDirectory.resolve("remote-evidence"));
    Files.writeString(
        remoteEvidence.resolve("run-status.json"),
        "{\"primaryStatus\":\"failed\",\"recoveryStatus\":\"failed\","
            + "\"cleanupStatus\":\"success\"}");
    Path git = writeGitSuccessStub(temporaryDirectory.resolve("git-success.ps1"), capture);
    Path ssh = writeSshFailureStub(temporaryDirectory.resolve("ssh-failure.cmd"), capture);
    Path scp =
        writeScpStub(
            temporaryDirectory.resolve("scp-success.ps1"), capture, remoteEvidence.resolve("run-status.json"));

    Process process = runnerProcess(runner, identity, environment, ssh, scp, git);
    String output = waitFor(process);

    assertThat(process.exitValue()).as(output).isNotZero();
    Path localStatus =
        temporaryDirectory.resolve("repo/output/stage4d").resolve(RUN_ID).resolve("run-status.json");
    JsonNode status = new ObjectMapper().readTree(localStatus.toFile());
    assertThat(status.path("primaryStatus").asText()).isEqualTo("failed");
    assertThat(status.path("recoveryStatus").asText()).isEqualTo("failed");
    assertThat(status.path("cleanupStatus").asText()).isEqualTo("success");
    assertThat(status.path("evidenceDownloadStatus").asText()).isEqualTo("success");
    assertThat(status.path("keyRemovalStatus").asText())
        .as(status.toPrettyString())
        .isEqualTo("success");
    assertThat(identity).doesNotExist();
    assertThat(Path.of(identity + ".pub")).doesNotExist();
    assertThat(Files.readString(capture)).doesNotContain(markerSecret).doesNotContain("private-key");
    assertThat(output).doesNotContain(markerSecret).doesNotContain("private-key");
  }

  private Path copyRunnerToTemporaryRepository() throws IOException {
    Path deploy = Files.createDirectories(temporaryDirectory.resolve("repo/deploy/stage4d"));
    Path runner = deploy.resolve("run-stage4d.ps1");
    Files.copy(DEPLOY.resolve("run-stage4d.ps1"), runner);
    Files.copy(DEPLOY.resolve("remove-temporary-ssh.ps1"), deploy.resolve("remove-temporary-ssh.ps1"));
    Files.copy(DEPLOY.resolve("remote-stage4d.sh"), deploy.resolve("remote-stage4d.sh"));
    return runner;
  }

  private Path writeCaptureStub(Path target, Path capture, String marker) throws IOException {
    Files.writeString(
        target,
        "param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Remaining)\n"
            + "Add-Content -LiteralPath '"
            + psQuote(capture)
            + "' -Value '"
            + marker
            + "'\nexit 0\n");
    return target;
  }

  private Path writeGitDirtyStub(Path target, Path capture) throws IOException {
    Files.writeString(
        target,
        "param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Remaining)\n"
            + "Add-Content -LiteralPath '"
            + psQuote(capture)
            + "' -Value ('git ' + ($Remaining -join ' '))\n"
            + "if ($Remaining -contains '--abbrev-ref') { Write-Output 'feat/stage-4d'; exit 0 }\n"
            + "if ($Remaining -contains '--porcelain') { Write-Output ' M dirty.txt'; exit 0 }\n"
            + "exit 0\n");
    return target;
  }

  private Path writeGitSuccessStub(Path target, Path capture) throws IOException {
    Files.writeString(
        target,
        "param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Remaining)\n"
            + "Add-Content -LiteralPath '"
            + psQuote(capture)
            + "' -Value ('git ' + ($Remaining -join ' '))\n"
            + "if ($Remaining -contains '--abbrev-ref') { Write-Output 'feat/stage-4d'; exit 0 }\n"
            + "if ($Remaining -contains '--porcelain') { exit 0 }\n"
            + "if ($Remaining -contains 'archive') { $index=[Array]::IndexOf($Remaining,'-o'); New-Item -ItemType File -Path $Remaining[$index + 1] -Force | Out-Null; exit 0 }\n"
            + "if ($Remaining -contains 'rev-parse') { Write-Output ('a' * 40); exit 0 }\n"
            + "exit 0\n");
    return target;
  }

  private Path writeSshFailureStub(Path target, Path capture) throws IOException {
    Files.writeString(
        target,
        "@echo off\r\n"
            + "echo ssh %*>>\""
            + capture
            + "\"\r\n"
            + "echo %*| findstr /C:\"remote-stage4d.sh\" >nul\r\n"
            + "if %errorlevel%==0 exit /b 23\r\n"
            + "exit /b 0\r\n");
    return target;
  }

  private Path writeScpStub(Path target, Path capture, Path remoteStatus) throws IOException {
    Files.writeString(
        target,
        "param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Remaining)\n"
            + "Add-Content -LiteralPath '"
            + psQuote(capture)
            + "' -Value ('scp ' + ($Remaining -join ' '))\n"
            + "if ($Remaining -contains '-r') { $destination=$Remaining[-1]; New-Item -ItemType Directory -Path $destination -Force | Out-Null; Copy-Item -LiteralPath '"
            + psQuote(remoteStatus)
            + "' -Destination (Join-Path $destination 'run-status.json') -Force }\n"
            + "exit 0\n");
    return target;
  }

  private Path writeCompleteEnvironment(String markerSecret) throws IOException {
    return Files.writeString(
        temporaryDirectory.resolve("complete-stage4d.env"),
        "DB_HOST=db.invalid\n"
            + "DB_PORT=3307\n"
            + "DB_NAME=volunteer_flow\n"
            + "DB_USERNAME=tester\n"
            + "DB_PASSWORD='"
            + markerSecret.replace("'", "\\'")
            + "'\n"
            + "JWT_SECRET=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=\n"
            + "SPRING_FLYWAY_ENABLED=false\n"
            + "REDIS_HOST=redis.invalid\n"
            + "REDIS_PORT=6379\n");
  }

  private Process runnerProcess(
      Path runner, Path identity, Path environment, Path ssh, Path scp, Path git)
      throws IOException {
    return new ProcessBuilder(
            requirePowerShell().toString(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            runner.toString(),
            "-VmHost",
            "vm.invalid",
            "-SshUser",
            "root",
            "-IdentityFile",
            identity.toString(),
            "-ComposeEnvFile",
            environment.toString(),
            "-RunId",
            RUN_ID,
            "-RepositoryRoot",
            temporaryDirectory.resolve("repo").toString(),
            "-SshCommand",
            ssh.toString(),
            "-ScpCommand",
            scp.toString(),
            "-GitCommand",
            git.toString())
        .redirectErrorStream(true)
        .start();
  }

  private String waitFor(Process process) throws Exception {
    boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(completed).as(output).isTrue();
    return output;
  }

  private static String read(String name) throws IOException {
    return Files.readString(DEPLOY.resolve(name));
  }

  private static String psQuote(Path path) {
    return path.toString().replace("'", "''");
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
