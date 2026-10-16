package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class Stage4DynamicCheckinContractTest {
  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();

  @Test
  void operationsGuideDocumentsConfigurationEndpointsFallbackAndEvidenceBoundary()
      throws IOException {
    String runbook = read("docs/operations/stage-4-redis.md");

    assertThat(runbook)
        .contains("DYNAMIC_CHECKIN_WINDOW")
        .contains("DYNAMIC_CHECKIN_TTL")
        .contains("DYNAMIC_CHECKIN_TOKEN_BYTES")
        .contains("30 秒")
        .contains("45 秒")
        .contains("GET /api/v1/check-in-sessions/{sessionId}/dynamic-code")
        .contains("POST /api/v1/check-in-sessions/{sessionId}/dynamic-check-ins")
        .contains("503 DYNAMIC_CHECKIN_UNAVAILABLE")
        .contains("人工补签")
        .contains("现有虚拟机 Redis")
        .contains("尚未完成真实手机与 Redis 联调验证");
  }

  @Test
  void deploymentExampleDeclaresDynamicCredentialSettingsWithoutRealAddresses()
      throws IOException {
    String environment = read("deploy/.env.example");

    assertThat(environment)
        .contains("DYNAMIC_CHECKIN_WINDOW=30s")
        .contains("DYNAMIC_CHECKIN_TTL=45s")
        .contains("DYNAMIC_CHECKIN_TOKEN_BYTES=24")
        .doesNotContain("192.168.");
  }

  @Test
  void fragmentAndReadinessContractsKeepTokensOutOfNginxAndRedisOptional()
      throws IOException {
    String fragment = read("frontend/src/checkin/dynamicCheckinFragment.ts");
    String application = read("backend/src/main/resources/application.yml");

    assertThat(fragment)
        .contains("url.hash = new URLSearchParams")
        .contains("history.replaceState(null, \"\", `${location.pathname}${location.search}`)")
        .doesNotContain("searchParams.set(\"token\"")
        .doesNotContain("searchParams.set(\"window\"");
    assertThat(application)
        .contains("include: readinessState,db")
        .doesNotContain("include: readinessState,db,redis");
  }

  @Test
  void sourceContractsRedactCredentialsAndCleanupLogsNoExceptionMessage()
      throws IOException {
    String store = read("backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinTokenStore.java");
    String admission = read("backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinService.java");
    String issued = read("backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinCodeService.java");
    String cleanup = read("backend/src/main/java/com/volunteerflow/checkin/DynamicCheckinTokenInvalidator.java");

    assertThat(store).contains("token=[REDACTED]");
    assertThat(admission).contains("token=[REDACTED]");
    assertThat(issued).contains("token=[REDACTED]");
    assertThat(cleanup)
        .contains("failureType={}")
        .doesNotContain("exception.getMessage()")
        .doesNotContain("tokens.evictRecent(sessionId, closedAt), exception");
  }

  private static String read(String path) throws IOException {
    return Files.readString(REPOSITORY_ROOT.resolve(path));
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
