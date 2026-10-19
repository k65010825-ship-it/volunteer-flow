package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.*;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import com.fasterxml.jackson.databind.json.JsonMapper;

class KafkaEventConfigurationTest {
  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
      .withUserConfiguration(KafkaEventConfiguration.class, EventTopicRouter.class);

  @Test
  void startsWithoutBrokerAndUsesSafeProducerDefaults() {
    runner.run(context -> {
      assertThat(context).hasNotFailed().hasSingleBean(KafkaEventGateway.class);
      KafkaEventProperties properties = context.getBean(KafkaEventProperties.class);
      assertThat(properties.getBatchSize()).isEqualTo(100); assertThat(properties.getLease()).isEqualTo(Duration.ofSeconds(60));
      assertThat(properties.getSendTimeout()).isEqualTo(Duration.ofSeconds(10));
      assertThat(context.getBean(ProducerFactory.class).getConfigurationProperties())
          .containsEntry(ProducerConfig.ACKS_CONFIG, "all").containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
      assertThat(context).hasSingleBean(KafkaAdmin.class);
    });
  }

  @Test
  void bindsExternalEventSettings() {
    runner.withPropertyValues("volunteerflow.events.bootstrap-servers=127.0.0.1:19092", "volunteerflow.events.client-id=relay-test",
        "volunteerflow.events.batch-size=7", "volunteerflow.events.lease=20s", "volunteerflow.events.send-timeout=2s",
        "volunteerflow.events.topics.registration=custom-registration")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(KafkaEventProperties.class).getBatchSize()).isEqualTo(7);
          assertThat(context.getBean(KafkaEventProperties.class).getLease()).isEqualTo(Duration.ofSeconds(20));
          assertThat(context.getBean(EventTopicRouter.class).topicFor("registration.submitted.v1")).isEqualTo("custom-registration");
          assertThat(context.getBean(ProducerFactory.class).getConfigurationProperties())
              .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:19092").containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "relay-test");
        });
  }

  @Test
  void rejectsUnsafeDurationsAndUnboundedBatchSettings() {
    for (String property : new String[]{"batch-size=0", "batch-size=10001", "lease=1s", "send-timeout=0s", "retry-initial=10m", "retention-batch-size=0"}) {
      runner.withPropertyValues("volunteerflow.events." + property).run(context -> assertThat(context).hasFailed());
    }
  }

  @Test
  void producerFailureDoesNotLogPayloadOrRawBrokerException() {
    MockProducer<String, String> producer = new MockProducer<>(false, new StringSerializer(), new StringSerializer());
    DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(java.util.Map.of()) {
      @Override public org.apache.kafka.clients.producer.Producer<String, String> createProducer() { return producer; }
    };
    KafkaTemplate<String, String> template = new KafkaEventConfiguration().eventKafkaTemplate(factory);
    Logger logger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> capture = new ListAppender<>(); capture.start(); logger.addAppender(capture);
    try {
      var future = new SpringKafkaEventGateway(template).publish("test-topic", "1001:3001", "private-event-content");
      producer.errorNext(new IllegalStateException("password=private-broker-secret"));
      assertThat(future).isCompletedExceptionally();
      assertThat(capture.list).allSatisfy(event -> {
        assertThat(event.getFormattedMessage()).doesNotContain("private-event-content", "private-broker-secret");
        assertThat(event.getThrowableProxy()).isNull();
      });
    } finally { logger.detachAppender(capture); capture.stop(); template.destroy(); factory.destroy(); }
  }

  @Test
  void blockedRelayDoesNotDelayRetentionOrOtherMaintenance() {
    RelayFixture f = new RelayFixture(); f.row(1, "PENDING");
    SchedulingObservation observation = new SchedulingObservation();
    KafkaEventGateway blockedBroker = (topic, key, json) -> {
      observation.relayThread = Thread.currentThread().getName();
      observation.started.countDown();
      try { observation.release.await(10, TimeUnit.SECONDS); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
      return CompletableFuture.completedFuture(new KafkaPublishReceipt(0, 0));
    };
    OutboxRelayService relay = new OutboxRelayService(f.claims(), new OutboxStateService(f.mapper, f.clock, f.properties),
        blockedBroker, new DomainEventCodec(JsonMapper.builder().findAndAddModules().build()),
        new EventTopicRouter("volunteerflow.activity-events.v1", "volunteerflow.registration-events.v1", "volunteerflow.checkin-events.v1"),
        f.properties, f.clock);
    try {
      runner.withUserConfiguration(SchedulingProbe.class, OutboxRelayJob.class)
          .withBean(OutboxRelayService.class, () -> relay).withBean(SchedulingObservation.class, () -> observation)
          .run(context -> {
            try {
              assertThat(observation.started.await(3, TimeUnit.SECONDS)).isTrue();
              assertThat(observation.maintenanceRanWhileBlocked.await(3, TimeUnit.SECONDS)).isTrue();
              assertThat(observation.relayThread).startsWith("outbox-relay-");
              assertThat(observation.maintenanceThread).startsWith("volunteerflow-maintenance-");
              assertThat(context.getBean("outboxRelayScheduler", ThreadPoolTaskScheduler.class).getPoolSize()).isEqualTo(1);
              assertThat(context.getBean("taskScheduler", ThreadPoolTaskScheduler.class).getPoolSize()).isEqualTo(1);
            } finally { observation.release.countDown(); }
          });
    } finally { observation.release.countDown(); }
  }

  static class SchedulingObservation {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch maintenanceRanWhileBlocked = new CountDownLatch(1);
    volatile String relayThread;
    volatile String maintenanceThread;
    @Scheduled(fixedDelay = 5)
    public void runMaintenance() {
      if (started.getCount() == 0 && release.getCount() == 1) {
        maintenanceThread = Thread.currentThread().getName(); maintenanceRanWhileBlocked.countDown();
      }
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableScheduling
  static class SchedulingProbe {
    @Bean OutboxRetentionJob retention(RelayFixture fixture) {
      return new OutboxRetentionJob(fixture.mapper, fixture.properties, fixture.clock);
    }
    @Bean RelayFixture fixture() { return new RelayFixture(); }
  }
}
