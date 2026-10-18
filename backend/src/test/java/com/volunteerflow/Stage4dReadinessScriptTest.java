package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Stage4dReadinessScriptTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();
  private static final Path READINESS_SCRIPT =
      REPOSITORY_ROOT.resolve("deploy/stage4d/wait-for-ready.sh");

  @TempDir Path temporaryDirectory;

  @Test
  void waitsForBothRecreatedBackendsAndThreeConsecutiveProxySuccesses() throws Exception {
    Path state = temporaryDirectory.resolve("state");
    Files.createDirectories(state);
    Path bin = Files.createDirectories(temporaryDirectory.resolve("bin"));
    writeExecutable(
        bin.resolve("docker"),
        "#!/usr/bin/env bash\n"
            + "set -eu\n"
            + "state=\"$STAGE4D_TEST_STATE\"\n"
            + "if [[ \"$*\" == *\" ps --quiet backend-a\"* ]]; then echo backend-a-id; exit 0; fi\n"
            + "if [[ \"$*\" == *\" ps --quiet backend-b\"* ]]; then echo backend-b-id; exit 0; fi\n"
            + "if [[ \"$*\" == *\"inspect --format\"*\"backend-a-id\"* ]]; then\n"
            + "  count=$(cat \"$state/backend-a-count\" 2>/dev/null || echo 0); count=$((count+1)); echo \"$count\" > \"$state/backend-a-count\"\n"
            + "  if (( count < 2 )); then echo starting; else echo healthy; fi; exit 0\n"
            + "fi\n"
            + "if [[ \"$*\" == *\"inspect --format\"*\"backend-b-id\"* ]]; then echo healthy; exit 0; fi\n"
            + "exit 1\n");
    writeExecutable(
        bin.resolve("curl"),
        "#!/usr/bin/env bash\n"
            + "set -eu\n"
            + "state=\"$STAGE4D_TEST_STATE\"\n"
            + "count=$(cat \"$state/curl-count\" 2>/dev/null || echo 0); count=$((count+1)); echo \"$count\" > \"$state/curl-count\"\n"
            + "if (( count == 2 )); then exit 22; fi\n"
            + "printf '{\"status\":\"UP\"}'\n");
    writeExecutable(bin.resolve("sleep"), "#!/usr/bin/env bash\nexit 0\n");
    Path readiness = temporaryDirectory.resolve("readiness.json");

    ProcessBuilder builder =
        new ProcessBuilder(
                requireBash().toString(),
                READINESS_SCRIPT.toString(),
                "stage4d-project",
                REPOSITORY_ROOT.resolve("deploy/stage4d/docker-compose.yml").toString(),
                temporaryDirectory.resolve("stage4d.env").toString(),
                "8088",
                readiness.toString(),
                bashPath(bin.resolve("docker")),
                bashPath(bin.resolve("curl")),
                bashPath(bin.resolve("sleep")))
            .redirectErrorStream(true);
    builder.environment().put("STAGE4D_TEST_STATE", bashPath(state));
    Process process = builder.start();
    boolean completed = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(completed).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(Files.readString(state.resolve("backend-a-count"))).isEqualTo("6\n");
    assertThat(Files.readString(state.resolve("curl-count"))).isEqualTo("5\n");
    assertThat(Files.readString(readiness)).isEqualTo("{\"status\":\"UP\"}");
  }

  private void writeExecutable(Path path, String contents) throws IOException {
    Files.writeString(path, contents);
    path.toFile().setExecutable(true);
  }

  private static Path requireBash() {
    Path bash = Path.of("C:/Program Files/Git/bin/bash.exe");
    Assumptions.assumeTrue(Files.isRegularFile(bash));
    return bash;
  }

  private static String bashPath(Path path) {
    String windows = path.toAbsolutePath().toString().replace('\\', '/');
    return "/" + Character.toLowerCase(windows.charAt(0)) + windows.substring(2);
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
