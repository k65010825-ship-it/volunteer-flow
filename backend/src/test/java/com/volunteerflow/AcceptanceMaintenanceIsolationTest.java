package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.checkin.*;
import com.volunteerflow.infrastructure.event.*;
import com.volunteerflow.registration.*;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;

/** These contexts deliberately do not enable scheduling or connect to external services. */
class AcceptanceMaintenanceIsolationTest {
  private static final List<Class<?>> JOBS =
      List.of(
          ActivityFinalizationJob.class,
          PromotionExpiryJob.class,
          PromotionReconciliationJob.class,
          OutboxRelayJob.class,
          OutboxRetentionJob.class);
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(Jobs.class);

  @Test
  void productionDefaultStillRegistersAllMaintenanceJobs() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          JOBS.forEach(type -> assertThat(context.getBeansOfType(type)).hasSize(1));
        });
  }

  @Test
  void explicitDisableRemovesAllFiveJobsOutsideTestProfile() {
    runner
        .withPropertyValues(
            "volunteerflow.maintenance.enabled=false", "spring.profiles.active=acceptance")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              JOBS.forEach(type -> assertThat(context.getBeansOfType(type)).isEmpty());
              assertThat(context.getEnvironment().getActiveProfiles()).doesNotContain("test");
            });
  }

  @Test
  void productionEventPublisherIsNotExcludedByAcceptanceProfile() {
    assertThat(TransactionalOutboxEventPublisher.class.getAnnotation(Profile.class).value())
        .containsExactly("!test");
    assertThat(PromotionReconciliationRequester.class.getAnnotation(Profile.class).value())
        .containsExactly("!test");
  }

  @Configuration(proxyBeanMethods = false)
  @Import({
    ActivityFinalizationJob.class,
    PromotionExpiryJob.class,
    PromotionReconciliationJob.class,
    OutboxRelayJob.class,
    OutboxRetentionJob.class
  })
  static class Jobs {
    @Bean
    ActivityMapper activities() {
      return mock(ActivityMapper.class);
    }

    @Bean
    ActivityFinalizationService finalizer() {
      return mock(ActivityFinalizationService.class);
    }

    @Bean
    ActivityFinalizationProperties finalizationProperties() {
      return new ActivityFinalizationProperties();
    }

    @Bean
    PromotionOfferMapper offers() {
      return mock(PromotionOfferMapper.class);
    }

    @Bean
    PromotionExpiryService expiry() {
      return mock(PromotionExpiryService.class);
    }

    @Bean
    PromotionExpiryProperties expiryProperties() {
      return new PromotionExpiryProperties();
    }

    @Bean
    PromotionReconciliationService reconciliation() {
      return mock(PromotionReconciliationService.class);
    }

    @Bean
    EventPipelineMetrics metrics() {
      return mock(EventPipelineMetrics.class);
    }

    @Bean
    OutboxRelayService relay() {
      return mock(OutboxRelayService.class);
    }

    @Bean
    OutboxEventMapper outbox() {
      return mock(OutboxEventMapper.class);
    }

    @Bean
    KafkaEventProperties eventProperties() {
      return new KafkaEventProperties();
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }
}
