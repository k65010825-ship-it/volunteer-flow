package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;

class PromotionExpiryConfigurationTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

  @Test
  void bindsDefaultDelayAndBatchLimit() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      var properties = context.getBean(PromotionExpiryProperties.class);
      assertThat(properties.getScanDelay()).isEqualTo(Duration.ofSeconds(30));
      assertThat(properties.getBatchSize()).isEqualTo(100);
    });
  }

  @Test
  void acceptsExternalDelayAndBatchLimit() {
    runner.withPropertyValues(
        "volunteerflow.registration.promotion-expiry.scan-delay=5s",
        "volunteerflow.registration.promotion-expiry.batch-size=25")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var properties = context.getBean(PromotionExpiryProperties.class);
          assertThat(properties.getScanDelay()).isEqualTo(Duration.ofSeconds(5));
          assertThat(properties.getBatchSize()).isEqualTo(25);
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"scan-delay=0s", "scan-delay=-1s", "scan-delay=1ns",
      "batch-size=0", "batch-size=-1"})
  void invalidConfigurationFailsStartup(String value) {
    runner.withPropertyValues("volunteerflow.registration.promotion-expiry." + value)
        .run(context -> assertThat(context).hasFailed());
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableConfigurationProperties(PromotionExpiryProperties.class)
  static class PropertiesConfiguration {}
}
