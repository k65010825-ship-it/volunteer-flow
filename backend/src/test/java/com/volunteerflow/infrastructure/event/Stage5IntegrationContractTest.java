package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Static harness configuration contract only; this is not Docker/MySQL runtime evidence. */
class Stage5IntegrationContractTest {
  @Test
  void disposableMysqlAllowsOrdinaryApplicationUserToInstallRollbackTriggers() throws Exception {
    String source = Files.readString(Path.of("src/integration-test/java/com/volunteerflow/integration/Stage5IntegrationSupport.java"));
    assertThat(source).contains(".withCommand(\"--log-bin-trust-function-creators=1\")");
    assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
        .doesNotContain("log-bin-trust-function-creators", "log_bin_trust_function_creators");
  }
}
