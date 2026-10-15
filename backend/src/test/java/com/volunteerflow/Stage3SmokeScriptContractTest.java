package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Stage3SmokeScriptContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @TempDir Path temporaryDirectory;

  @Test
  void smokeUsesBasicParsingForRootAndDeepLinkOnWindowsPowerShell51() throws Exception {
    Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("windows"));
    Path powershell =
        Path.of(
            System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
            "System32",
            "WindowsPowerShell",
            "v1.0",
            "powershell.exe");
    Assumptions.assumeTrue(Files.isRegularFile(powershell));

    Path smoke = temporaryDirectory.resolve("smoke-test.ps1");
    Files.copy(REPOSITORY_ROOT.resolve("deploy/smoke-test.ps1"), smoke);
    Path driver = temporaryDirectory.resolve("driver.ps1");
    Files.writeString(
        driver,
        "param([string]$Smoke)\n"
            + "function Invoke-RestMethod { [pscustomobject]@{ status = 'UP' } }\n"
            + "function Invoke-WebRequest {\n"
            + "  param([string]$Method,[string]$Uri,[int]$TimeoutSec,[string]$ErrorAction,[switch]$UseBasicParsing)\n"
            + "  if (-not $UseBasicParsing) { throw 'UseBasicParsing missing' }\n"
            + "  $content = if ($Uri.EndsWith('/notifications')) { '<div id=\"app\"></div>' } else { 'root' }\n"
            + "  [pscustomobject]@{ StatusCode = 200; Content = $content }\n"
            + "}\n"
            + "& $Smoke -BaseUrl 'http://stage3.invalid'\n");

    Process process =
        new ProcessBuilder(
                powershell.toString(),
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                driver.toString(),
                "-Smoke",
                smoke.toString())
            .redirectErrorStream(true)
            .start();
    boolean completed = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertThat(completed).as(output).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(output).contains("Stage 3 public-entry smoke checks passed.");
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
