package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.volunteerflow.registration.PromotionReconcileTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EventPipelineHealthIndicatorTest {
  @Test
  void productionReadinessRemainsUpWithDegradedPipelineAndRedis() {
    var observation = new EventPipelineTestMetrics();
    observation.metrics.publishFailed();
    new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(HealthEndpointAutoConfiguration.class))
        .withPropertyValues("spring.config.import=", "management.endpoint.health.show-components=always")
        .withBean("db", HealthIndicator.class, () -> () -> Health.up().build())
        .withBean("redis", HealthIndicator.class, () -> () -> Health.down().build())
        .withBean("readinessState", HealthIndicator.class, () -> () -> Health.up().build())
        .withBean("livenessState", HealthIndicator.class, () -> () -> Health.up().build())
        .withBean("eventPipeline", HealthIndicator.class, () -> new EventPipelineHealthIndicator(observation.metrics))
        .run(context -> {
          assertThat(context).hasNotFailed();
          var endpoint = context.getBean(HealthEndpoint.class);
          var readiness = (CompositeHealth) endpoint.healthForPath("readiness");
          assertThat(readiness.getStatus()).isEqualTo(Status.UP);
          assertThat(readiness.getComponents()).containsOnlyKeys("readinessState", "db");
          assertThat(endpoint.healthForPath("event-pipeline").getStatus()).isEqualTo(Status.DOWN);
        });
  }

  @Test
  void reportsUnknownBeforeEvidenceAndDegradedAfterFailureWithoutAnyBrokerProbe() {
    var metrics = new EventPipelineMetrics(new SimpleMeterRegistry(), mock(OutboxEventMapper.class),
        mock(PromotionReconcileTaskMapper.class), Clock.systemUTC());
    var health = new EventPipelineHealthIndicator(metrics);
    assertThat(health.health().getStatus()).isEqualTo(Status.UNKNOWN);
    metrics.publishFailed();
    assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.health().getDetails()).containsEntry("category", "BROKER_PUBLISH_FAILED");
    metrics.publishSucceeded();
    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    assertThat(health.health().getDetails()).containsEntry("diagnosticOnly", true);
  }
}
