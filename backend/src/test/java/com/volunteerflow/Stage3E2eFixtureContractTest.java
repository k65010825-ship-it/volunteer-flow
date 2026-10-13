package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage3E2eFixtureContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

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
        .contains("-Dstage3.e2e.action=$Action")
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
  void browserSpecUsesPublicNginxWithoutMocksOrSecretArtifacts() throws IOException {
    String config = Files.readString(REPOSITORY_ROOT.resolve("frontend/playwright.config.ts"));
    String spec = Files.readString(REPOSITORY_ROOT.resolve("frontend/e2e/stage3.spec.ts"));

    assertThat(config)
        .contains("process.env.PLAYWRIGHT_BASE_URL")
        .contains("trace: \"on-first-retry\"")
        .contains("screenshot: \"only-on-failure\"")
        .doesNotContain("webServer");
    assertThat(spec)
        .contains("process.env.S3_E2E_RUN_ID")
        .contains("redirects a protected notification deep link to login through Nginx")
        .contains("test.use({ trace: \"off\", screenshot: \"off\" })")
        .contains("/notifications")
        .contains("/check-in-management")
        .contains("Backend logs contained plaintext check-in code")
        .doesNotContain("page.route(")
        .doesNotContain("context.route(")
        .doesNotContain("console.log");
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
}
