package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class Stage4dK6ContractTest {

  private static final Path REPOSITORY_ROOT = locateRepositoryRoot();
  private static final Path LOAD_TEST = REPOSITORY_ROOT.resolve("loadtest/stage4d");
  private static final List<String> SCENARIOS =
      List.of("registration.js", "rate-limit.js", "recovery.js");

  @Test
  void sharedConfigurationFailsClosedBeforeLoadBegins() throws IOException {
    String config = read("lib/config.js");

    assertThat(config)
        .contains("export function requiredEnv(name)")
        .contains("requiredEnv(\"BASE_URL\")")
        .contains("requiredEnv(\"RUN_ID\")")
        .contains("requiredEnv(\"DATASET\")")
        .contains("requiredEnv(\"MANIFEST_PATH\")")
        .contains("requiredEnv(\"TOKENS_PATH\")")
        .contains("/^https?:\\/\\//")
        .contains("canonical lowercase UUID")
        .contains("manifest.runId !== RUN_ID")
        .contains("manifest.dataset !== DATASET")
        .contains("token user IDs must exactly match manifest member IDs")
        .contains("open(MANIFEST_PATH)")
        .contains("open(TOKENS_PATH)")
        .doesNotContain("console.log")
        .doesNotContain("console.error");
  }

  @Test
  void everyScenarioUsesSharedValidatedInputsWithoutEmbeddingCredentials() throws IOException {
    for (String scenario : SCENARIOS) {
      String source = read(scenario);
      assertThat(source)
          .as(scenario)
          .contains("from \"./lib/config.js\"")
          .doesNotContain("192.168.")
          .doesNotContain("localhost")
          .doesNotContain("Bearer ey")
          .doesNotContain("password")
          .doesNotContain("username")
          .doesNotContain("console.log")
          .doesNotContain("console.error")
          .doesNotContain("open(");
    }
  }

  @Test
  void registrationUsesOneUniqueMemberPerVuAndAcceptsOnlyCreated() throws IOException {
    String registration = read("registration.js");

    assertThat(registration)
        .contains("vus: 100")
        .contains("iterations: 1")
        .contains("startAt")
        .contains("waitForStart")
        .contains("manifest.members[__VU - 1]")
        .contains("/api/v1/activities/${manifest.activityId}/registrations")
        .contains("positionId: manifest.positionId")
        .contains("response.status !== 201")
        .contains("business_checks_failed")
        .contains("unexpected_status")
        .contains("unexpected_status: [\"count==0\"]")
        .contains("export function handleSummary(data)");
    assertThat(countOccurrences(registration, "http.post(")).isEqualTo(1);
  }

  @Test
  void rateLimitSeparatesAuthenticationAndThrottleOutcomes() throws IOException {
    String rateLimit = read("rate-limit.js");

    assertThat(rateLimit)
        .contains("authenticationResponse.status !== 401")
        .contains("response.status === 429")
        .contains("Retry-After")
        .contains("rateLimitAllowed.add(1)")
        .contains("rateLimitRejected.add(1)")
        .contains("unexpected_status")
        .contains("business_checks_failed")
        .contains("EXPECTED_RATE_LIMIT_ALLOWED")
        .contains("EXPECTED_RATE_LIMIT_REJECTED")
        .contains("export function handleSummary(data)");
  }

  @Test
  void recoveryChecksReadinessActivityAndAuthenticatedRegistrationState() throws IOException {
    String recovery = read("recovery.js");

    assertThat(recovery)
        .contains("/actuator/health/readiness")
        .contains("/api/v1/activities/${manifest.activityId}")
        .contains("/api/v1/users/me/registrations")
        .contains("authHeaders(")
        .contains("response.status === 200")
        .contains("unexpected_status: [\"count==0\"]")
        .contains("export function handleSummary(data)");
  }

  @Test
  void summariesContainMetricsButNeverFixtureTokens() throws IOException {
    String config = read("lib/config.js");
    String http = read("lib/http.js");

    assertThat(config)
        .contains("export function writeSummary(data, scenario)")
        .contains("/output/${scenario}-${RUN_ID}-${DATASET}-summary.json")
        .contains("metric.values")
        .doesNotContain("accessToken:")
        .doesNotContain("tokens:");
    assertThat(http)
        .contains("export const businessChecksFailed")
        .contains("export const unexpectedStatus")
        .contains("export const rateLimitAllowed")
        .contains("export const rateLimitRejected")
        .contains("Authorization: `Bearer ${token}`")
        .doesNotContain("console.");
  }

  private static String read(String relativePath) throws IOException {
    return Files.readString(LOAD_TEST.resolve(relativePath));
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
