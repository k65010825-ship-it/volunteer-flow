package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Stage5RunnerContractTest {
  private static final Path DEPLOY = Stage5DeploymentContractTest.ROOT.resolve("deploy/stage5");
  @TempDir Path temporary;

  private String read(String name) throws Exception {
    Path path = DEPLOY.resolve(name);
    assertThat(path).as("Task9A artifact: " + name).exists();
    return Files.readString(path);
  }

  @Test
  void pipeBridgeRejectsHostFileAndMysqlClientCommandAttacksOffline() throws Exception {
    read("test_pipe_bridge.py");
    Process p = new ProcessBuilder("python", "-B", DEPLOY.resolve("test_pipe_bridge.py").toString())
        .redirectErrorStream(true).start();
    boolean done = p.waitFor(180, TimeUnit.SECONDS);
    if (!done) { p.destroyForcibly(); }
    assertThat(done).as("adversarial pipe tests have a total deadline").isTrue();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.exitValue()).as(output).isZero();
    assertThat(output).contains("OK").doesNotContain("FAILED");
    assertThat(read("python-runtime.sh")).contains("--user 65534:65534", "--pipe", "stage5_require_root",
        "--network none", "--read-only", "--log-driver none")
        .doesNotContain("request.ready", "stage5_bridge_request", "--bridge");
    assertThat(read("pipe-bridge.sh")).contains("--binary-mode=1", "--defaults-file=", "65534:65534",
        "stage5_sql_safe", "stage5_pipe_read_frame", "stage5_kafka_action", "stage5_project_safe",
        "kafka-list-topics", "kafka-produce-duplicate", "kafka-describe-group",
        "delivery.timeout.ms=30000", "STAGE5_KAFKA_LIMIT=45")
        .doesNotContain("--commands=OFF", "eval ", "source \"$root/stage5.env\"");
    String broker = read("pipe-bridge.sh");
    assertThat(broker.substring(broker.indexOf("stage5_bridge_allowed()"),
        broker.indexOf("stage5_project_safe()"))).doesNotContain("/opt/kafka/bin/", "--topic", "--group");
    String remote = read("remote-stage5.sh");
    assertThat(remote).contains("stage5_prepare_topics \"$remote_root\" \"$compose_project\"",
        "Bash 4.2+ required", "bash-version.txt", "bashVersion=%s");
    assertThat(remote.indexOf("stage5_prepare_topics \"$remote_root\""))
        .isLessThan(remote.indexOf("driver topics"));
  }

  @Test
  void nulDelimitedArgvCompatibilityContractPassesOnCurrentBash() throws Exception {
    read("test-bash42-compatibility.sh");
    Process p = new ProcessBuilder(bash(), DEPLOY.resolve("test-bash42-compatibility.sh").toString(),
        DEPLOY.resolve("pipe-bridge.sh").toString()).redirectErrorStream(true).start();
    String output = finish(p);
    assertThat(p.exitValue()).as(output).isZero();
    assertThat(output).contains(
        "PASS bash42_nul_argv_preserves_empty_argument",
        "PASS bash42_missing_terminal_nul_is_rejected",
        "PASS bash42_more_than_128_arguments_is_rejected",
        "TEST_RUNTIME_BASH=").doesNotContain("FAIL bash42_");
  }

  @Test
  void collectorOwnershipContractPassesOnCurrentBash() throws Exception {
    read("test-bash42-collector-wait.sh");
    Process p = new ProcessBuilder(bash(), DEPLOY.resolve("test-bash42-collector-wait.sh").toString(),
        DEPLOY.resolve("pipe-bridge.sh").toString()).redirectErrorStream(true).start();
    boolean done = p.waitFor(45, TimeUnit.SECONDS);
    if (!done) { p.destroyForcibly(); }
    assertThat(done).as("collector ownership harness has a total deadline").isTrue();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.exitValue()).as(output).isZero();
    assertThat(output).contains(
        "PASS collector_fast_stdout_stderr_concurrent",
        "PASS collector_stdout_cap_is_hard",
        "PASS collector_stderr_cap_is_hard",
        "PASS collector_deadline_fails_closed",
        "PASS collector_early_rejection_is_bounded",
        "TEST_RUNTIME_BASH=")
        .doesNotContain("FAIL collector_", "collector-private-marker", "not a child of this shell");
  }

  @Test
  void remotePythonRuntimeNeedsNoHostPythonAndFailsClosedOffline() throws Exception {
    read("test_runtime.py");
    Process p = new ProcessBuilder("python", "-B", DEPLOY.resolve("test_runtime.py").toString())
        .redirectErrorStream(true).start();
    String output = finish(p);
    assertThat(p.exitValue()).as(output).isZero();
    assertThat(output).contains("OK").doesNotContain("FAILED");
    assertThat(read("python-runtime.sh")).contains(
        "STAGE5_PYTHON_OFFLINE_TAG='python:i-was-a-digest'",
        "STAGE5_PYTHON_INDEX_DIGEST='sha256:a58daefb915e1e03ad48f3ca4df8832065412c5c35cacb9d39f4229184de12b6'",
        "STAGE5_PYTHON_MANIFEST_DIGEST='sha256:3121f8b0804aa3698ab750d9a39ea4a42657a385c9b133722b915e55c51551a6'",
        "STAGE5_PYTHON_CONFIG_DIGEST='sha256:64f864dc81f0907bda2e1f08d208687d261f70ad0a67dbe56d88598f31ed7243'",
        "STAGE5_PYTHON_APPROVED_HISTORICAL_ARCHIVE_SHA256='sha256:437e3a92118aa1e01954b202f11ac592d13da1105171a9fc480dc10fc2620f3b'",
        "stage5_python_offline_identity_allowed",
        "\"$STAGE5_PYTHON_RUNTIME_IMAGE\" python -B /acceptance.py")
        .doesNotContain("\"$STAGE5_PYTHON_OFFLINE_TAG\" python -B /acceptance.py");
  }

  @Test
  void windowsPowerShell51RunsStrictLocalPreflightWithoutNetworkOrCredentialSideEffects() throws Exception {
    read("test-powershell51-compatibility.ps1");
    Path ps = Path.of(System.getenv().getOrDefault("SystemRoot", "C:/Windows"),
        "System32/WindowsPowerShell/v1.0/powershell.exe");
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(ps),
        "Actual Windows PowerShell 5.1 is required; pwsh is not substitute evidence");
    Process p = new ProcessBuilder(ps.toString(), "-Version", "5.1", "-NoProfile", "-NonInteractive",
        "-File", DEPLOY.resolve("test-powershell51-compatibility.ps1").toString())
        .redirectErrorStream(true).start();
    boolean done = p.waitFor(180, TimeUnit.SECONDS);
    if (!done) { p.destroyForcibly(); }
    assertThat(done).as("PowerShell 5.1 offline suite has a total deadline").isTrue();
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(p.exitValue()).as(output).isZero();
    assertThat(output).contains("PowerShell 5.1 compatibility offline PASS").doesNotContain("private-marker");
  }

  @Test
  void nativeExecutableDiscoverySelectsOneApplicationAndRejectsUnsafeCandidates() throws Exception {
    Path ps = Path.of(System.getenv().getOrDefault("SystemRoot", "C:/Windows"),
        "System32/WindowsPowerShell/v1.0/powershell.exe");
    if (!Files.exists(ps)) { ps = Path.of("pwsh"); }
    read("test-native-tools.ps1");
    Process p = new ProcessBuilder(ps.toString(), "-NoProfile", "-NonInteractive", "-File",
        DEPLOY.resolve("test-native-tools.ps1").toString()).redirectErrorStream(true).start();
    String output = finish(p);
    assertThat(p.exitValue()).as(output).isZero();
    assertThat(output).contains("Native resolver offline PASS").doesNotContain("private-marker");
    assertThat(read("run-stage5.ps1")).contains("../lib/native-tools.psm1",
        "Resolve-ApprovedApplication -Name git", "Resolve-ApprovedApplication -Name python",
        "Resolve-ApprovedApplication -Name ssh", "Resolve-ApprovedApplication -Name scp")
        .doesNotContain("(Get-Command");
    assertThat(Files.readString(DEPLOY.resolve("../stage4d/bootstrap-temporary-ssh.ps1")))
        .contains("../lib/native-tools.psm1", "Resolve-ApprovedApplication -Name ssh",
            "Resolve-ApprovedApplication -Name ssh-keygen")
        .doesNotContain("(Get-Command");
  }

  @Test
  void offlineDriverTestsExerciseEnvironmentOwnershipAuditAndSecretBoundaries() throws Exception {
    read("test_acceptance.py");
    Process p = new ProcessBuilder("python", "-B", DEPLOY.resolve("test_acceptance.py").toString())
        .redirectErrorStream(true).start();
    assertThat(finish(p)).contains("OK").doesNotContain("FAILED");
    assertThat(p.exitValue()).isZero();
  }

  @Test
  void orchestrationHasSeparateBackupMigrationAndLiveGatesAndScopedEvidence() throws Exception {
    String local = read("run-stage5.ps1");
    String remote = read("remote-stage5.sh");
    assertThat(local).contains("AuthorizeLiveRun", "MigrationReviewed",
        "feat/stage-5", "--porcelain", "archive", "evidenceDownloadStatus", "keyRemovalStatus",
        "ConnectTimeout=10", "BatchMode=yes", "remove-temporary-key.py",
        "$keyRemovalExitCode = $LASTEXITCODE", "if ($keyRemovalExitCode -ne 0)",
        "keyRemovalExitCode=$keyRemovalExitCode")
        .doesNotContain("StrictHostKeyChecking=no", "Invoke-Expression");
    assertThat(remote).contains("set -Eeuo pipefail", "umask 077", "--authorize-live-run",
        "--migration-reviewed",
        "compose config --no-interpolate", "compose stop kafka", "compose start kafka",
        "compose down --volumes", "metrics-", "run-status.json", "acceptance.py", "cleanup", "timeout")
        .doesNotContain("source \"$compose_env\"", "eval ", "rm -rf", "FLUSHALL", "TRUNCATE",
            "flyway migrate", "mysql <", "docker stop mysql", "docker stop redis");
    assertThat(remote.indexOf("backup_database\n")).isLessThan(remote.indexOf("verify_migration_gate\n"));
    assertThat(remote).contains("normal", "outage", "recover", "duplicate", "reconcile", "assert-clean");
    assertThat(read("acceptance.py")).contains("backup.sha256", "fingerprint-before.sha256",
        "fingerprint-after.sha256", "environment-redacted.txt", "offsets-", "secret-scan.txt");
  }

  @Test
  void readinessAndReplayAreBoundedAndReplayNeverChangesConsumerGroupOffsets() throws Exception {
    String wait = read("wait-for-stage5.sh");
    String replay = read("replay-dlt.sh");
    assertThat(wait).contains("attempt", "timeout", "readiness", "backend-a", "backend-b");
    assertThat(replay).contains("--topic", "--partition", "--start-offset", "--end-offset", "--confirm",
        "--dry-run", "1000", "timeout", "--max-messages", "parse.key=true", "key.separator=",
        "enable.auto.commit=false", "\"$STAGE5_PYTHON_RUNTIME_IMAGE\" python -B -")
        .doesNotContain("--reset-offsets", "--from-beginning", "eval ");
  }

  @Test
  void runnerRefusesWithoutLiveAuthorizationBeforeLookingForCredentials() throws Exception {
    read("run-stage5.ps1");
    Path ps = Path.of(System.getenv().getOrDefault("SystemRoot", "C:/Windows"),
        "System32/WindowsPowerShell/v1.0/powershell.exe");
    if (!Files.exists(ps)) { ps = Path.of("pwsh"); }
    Process p = new ProcessBuilder(ps.toString(), "-NoProfile", "-NonInteractive", "-File",
        DEPLOY.resolve("run-stage5.ps1").toString(), "-VmHost", "vm.invalid", "-IdentityFile",
        "missing-key", "-ComposeEnvFile", "missing.env").redirectErrorStream(true).start();
    String output = finish(p);
    assertThat(p.exitValue()).as(output).isNotZero();
    assertThat(output).contains("AuthorizeLiveRun").doesNotContain("Could not resolve hostname");
    Process nonRoot = new ProcessBuilder(ps.toString(), "-NoProfile", "-NonInteractive", "-File",
        DEPLOY.resolve("run-stage5.ps1").toString(), "-VmHost", "vm.invalid", "-IdentityFile",
        "missing-key", "-ComposeEnvFile", "missing.env", "-SshUser", "other", "-AuthorizeLiveRun")
        .redirectErrorStream(true).start();
    assertThat(finish(nonRoot)).contains("requires SSH root").doesNotContain("Could not resolve hostname");
    assertThat(nonRoot.exitValue()).isNotZero();
  }

  @Test
  void bundledDriverRejectsInvalidRunScopeBeforeAnyDatabaseOrDockerAccess() throws Exception {
    read("acceptance.py");
    Process p = new ProcessBuilder("python", DEPLOY.resolve("acceptance.py").toString(),
        "--run-id", "../../unsafe", "--root", temporary.toString(), "--env-file", "missing.env",
        "--project", "vf5unsafe", "normal").redirectErrorStream(true).start();
    assertThat(finish(p)).contains("invalid run scope").doesNotContain("missing.env");
    assertThat(p.exitValue()).isNotZero();
  }

  @Test
  void replayRejectsMissingReversedAndUnboundedRangesAndDryRunDoesNotCallDocker() throws Exception {
    read("replay-dlt.sh");
    String bash = bash();
    List<String> valid = List.of("--project", "vf5" + "a".repeat(32), "--compose-file", "missing.yml",
        "--env-file", "missing.env", "--topic", "volunteerflow.registration-events.v1.DLT",
        "--partition", "0", "--start-offset", "2", "--end-offset", "5");
    for (List<String> args : List.of(List.<String>of(),
        List.of("--topic", "x.DLT", "--partition", "0", "--start-offset", "5", "--end-offset", "2"),
        List.of("--topic", "x.DLT", "--partition", "0", "--start-offset", "0", "--end-offset", "1001"))) {
      Process p = shell(bash, args);
      assertThat(finish(p)).doesNotContain("BEGIN PRIVATE KEY");
      assertThat(p.exitValue()).isNotZero();
    }
    var args = new java.util.ArrayList<>(valid); args.add("--dry-run");
    Process p = shell(bash, args);
    assertThat(finish(p)).contains("DRY_RUN", "[2,5)").doesNotContain("not found");
    assertThat(p.exitValue()).isZero();
    Process noConfirm = shell(bash, valid);
    assertThat(finish(noConfirm)).contains("confirm");
    assertThat(noConfirm.exitValue()).isNotZero();
  }

  @Test
  void remoteRefusesInvalidScopeAndMissingLiveGateWithoutCallingDocker() throws Exception {
    read("remote-stage5.sh");
    Process p = new ProcessBuilder(bash(), DEPLOY.resolve("remote-stage5.sh").toString(),
        "--run-id", "../../unsafe").redirectErrorStream(true).start();
    String output = finish(p);
    assertThat(p.exitValue()).as(output).isNotZero();
    assertThat(output).doesNotContain("docker: command not found");
  }

  private Process shell(String bash, List<String> arguments) throws Exception {
    var command = new java.util.ArrayList<>(List.of(bash, DEPLOY.resolve("replay-dlt.sh").toString()));
    command.addAll(arguments);
    return new ProcessBuilder(command).redirectErrorStream(true).start();
  }

  private static String bash() {
    Path windows = Path.of("C:/Program Files/Git/bin/bash.exe");
    return Files.exists(windows) ? windows.toString() : "bash";
  }

  private static String finish(Process p) throws Exception {
    boolean done = p.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    if (!done) { p.destroyForcibly(); }
    assertThat(done).as("script must finish within 30 seconds").isTrue();
    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
  }
}
