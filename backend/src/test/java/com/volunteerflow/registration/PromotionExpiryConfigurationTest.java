package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

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

  @Test
  void nonTestProfileRegistersJobWithConfiguredDelay() {
    new ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfiguration.class)
        .withPropertyValues(
            "volunteerflow.registration.promotion-expiry.scan-delay=7s",
            "volunteerflow.registration.promotion-expiry.batch-size=25")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(PromotionExpiryJob.class);
          assertThat(context.getBean(PromotionExpiryProperties.class).getScanDelay())
              .isEqualTo(Duration.ofSeconds(7));

          var schedulingProcessor =
              context.getBean(ScheduledAnnotationBeanPostProcessor.class);
          assertThat(schedulingProcessor.getScheduledTasks())
              .singleElement()
              .satisfies(scheduledTask -> {
                assertThat(scheduledTask.getTask()).isInstanceOf(FixedDelayTask.class);
                FixedDelayTask fixedDelayTask = (FixedDelayTask) scheduledTask.getTask();
                assertThat(fixedDelayTask.getIntervalDuration())
                    .isEqualTo(Duration.ofSeconds(7));
              });
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

  @TestConfiguration(proxyBeanMethods = false)
  @EnableScheduling
  @EnableConfigurationProperties(PromotionExpiryProperties.class)
  @Import(PromotionExpiryJob.class)
  static class SchedulingConfiguration {
    @Bean
    PromotionOfferMapper promotionOfferMapper() {
      return mock(PromotionOfferMapper.class);
    }

    @Bean
    PromotionExpiryService promotionExpiryService() {
      return mock(PromotionExpiryService.class);
    }

    @Bean
    TaskScheduler taskScheduler() {
      return mock(TaskScheduler.class);
    }
  }
}
