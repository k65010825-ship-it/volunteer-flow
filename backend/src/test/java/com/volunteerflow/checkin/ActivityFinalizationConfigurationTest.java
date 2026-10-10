package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.volunteerflow.VolunteerFlowApplication;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.registration.PromotionExpiryProperties;
import java.time.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;

class ActivityFinalizationConfigurationTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withUserConfiguration(PropertiesConfiguration.class);

  @Test void defaultsBoundOneRunToOneThousandCandidates() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      var p = context.getBean(ActivityFinalizationProperties.class);
      assertThat(p.getBatchSize()).isEqualTo(100);
      assertThat(p.getMaxBatchesPerRun()).isEqualTo(10);
      assertThat(p.getScanDelay()).isEqualTo(Duration.ofSeconds(30));
    });
  }

  @ParameterizedTest @ValueSource(strings = {"batch-size=0", "batch-size=-1", "batch-size=501",
      "max-batches-per-run=0", "max-batches-per-run=-1", "max-batches-per-run=101",
      "scan-delay=0s", "scan-delay=-1s", "scan-delay=1ns"})
  void rejectsInvalidConfigurationAtStartup(String setting) {
    runner.withPropertyValues("volunteerflow.checkin.finalization." + setting)
        .run(context -> assertThat(context).hasFailed());
  }

  @ParameterizedTest @ValueSource(ints = {1, 500})
  void acceptsBatchBoundary(int batch) {
    runner.withPropertyValues("volunteerflow.checkin.finalization.batch-size=" + batch)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ActivityFinalizationProperties.class).getBatchSize()).isEqualTo(batch);
        });
  }

  @ParameterizedTest @ValueSource(ints = {1, 100})
  void acceptsMaxBatchesBoundary(int batches) {
    runner.withPropertyValues("volunteerflow.checkin.finalization.max-batches-per-run=" + batches)
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(ActivityFinalizationProperties.class).getMaxBatchesPerRun()).isEqualTo(batches);
        });
  }

  @Test void actualJobUsesExternalDelay() {
    new ApplicationContextRunner().withUserConfiguration(SchedulingConfiguration.class)
        .withPropertyValues("volunteerflow.checkin.finalization.scan-delay=7s",
            "volunteerflow.checkin.finalization.batch-size=25",
            "volunteerflow.checkin.finalization.max-batches-per-run=2")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(ActivityFinalizationJob.class);
          var p = context.getBean(ActivityFinalizationProperties.class);
          assertThat(p.getScanDelay()).isEqualTo(Duration.ofSeconds(7));
          assertThat(p.getBatchSize()).isEqualTo(25);
          assertThat(p.getMaxBatchesPerRun()).isEqualTo(2);
          assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
              .singleElement().satisfies(task -> assertThat(((FixedDelayTask) task.getTask()).getIntervalDuration())
                  .isEqualTo(Duration.ofSeconds(7)));
        });
  }

  @Test void applicationRegistersBothPropertyClasses() {
    assertThat(Arrays.asList(VolunteerFlowApplication.class.getAnnotation(EnableConfigurationProperties.class).value()))
        .contains(ActivityFinalizationProperties.class, PromotionExpiryProperties.class);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(ActivityFinalizationProperties.class)
  static class PropertiesConfiguration {}

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  @EnableConfigurationProperties(ActivityFinalizationProperties.class)
  @Import(ActivityFinalizationJob.class)
  static class SchedulingConfiguration {
    @Bean ActivityMapper activities() { return mock(ActivityMapper.class); }
    @Bean ActivityFinalizationService finalizer() { return mock(ActivityFinalizationService.class); }
    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean TaskScheduler taskScheduler() { return mock(TaskScheduler.class); }
  }
}
