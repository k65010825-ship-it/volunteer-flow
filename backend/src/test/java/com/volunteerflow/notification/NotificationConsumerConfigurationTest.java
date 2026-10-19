package com.volunteerflow.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.volunteerflow.infrastructure.event.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.SendResult;

class NotificationConsumerConfigurationTest {
  private final EventPipelineTestMetrics observation = new EventPipelineTestMetrics();
  final NotificationConsumerConfiguration configuration = new NotificationConsumerConfiguration();

  @Test
  void startsBrokerlessAndRegistersExactlyThreeTopicsWithRecordAcknowledgement() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
        .withUserConfiguration(KafkaEventConfiguration.class, NotificationConsumerConfiguration.class)
        .withBean(EventPipelineMetrics.class, () -> observation.metrics)
        .withBean(io.micrometer.core.instrument.MeterRegistry.class, () -> observation.registry)
        .withBean(DomainEventCodec.class, () -> f.codec)
        .withBean(NotificationEventHandler.class, () -> f.handler)
        .withBean(KafkaNotificationListener.class, () -> new KafkaNotificationListener(f.codec, f.handler, observation.metrics))
        .withPropertyValues("volunteerflow.events.consumer-auto-startup=false")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var registry = context.getBean(KafkaListenerEndpointRegistry.class);
          assertThat(registry.getListenerContainers()).hasSize(1);
          var container = registry.getListenerContainers().iterator().next();
          assertThat(container.isRunning()).isFalse();
          assertThat(container.getContainerProperties().getTopics()).containsExactlyInAnyOrder(
              "volunteerflow.activity-events.v1", "volunteerflow.registration-events.v1", "volunteerflow.checkin-events.v1");
          assertThat(container.getContainerProperties().getGroupId()).isEqualTo("volunteerflow-notification-v1");
          assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
          assertThat(container.getContainerProperties().isMissingTopicsFatal()).isFalse();
          assertThat(context.getBean("notificationConsumerFactory", ConsumerFactory.class).getConfigurationProperties())
              .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        });
  }

  @Test
  void bindsConfiguredTopicsAndConsumerGroupWithoutBroker() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
        .withUserConfiguration(KafkaEventConfiguration.class, NotificationConsumerConfiguration.class)
        .withBean(EventPipelineMetrics.class, () -> observation.metrics)
        .withBean(io.micrometer.core.instrument.MeterRegistry.class, () -> observation.registry)
        .withBean(DomainEventCodec.class, () -> f.codec).withBean(NotificationEventHandler.class, () -> f.handler)
        .withBean(KafkaNotificationListener.class, () -> new KafkaNotificationListener(f.codec, f.handler, observation.metrics))
        .withPropertyValues("volunteerflow.events.consumer-auto-startup=false", "volunteerflow.events.consumer-group=custom-group",
            "volunteerflow.events.topics.activity=custom-activity", "volunteerflow.events.topics.registration=custom-registration",
            "volunteerflow.events.topics.checkin=custom-checkin")
        .run(context -> {
          assertThat(context).hasNotFailed();
          var container = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers().iterator().next();
          assertThat(container.getContainerProperties().getTopics()).containsExactlyInAnyOrder("custom-activity", "custom-registration", "custom-checkin");
          assertThat(container.getContainerProperties().getGroupId()).isEqualTo("custom-group");
        });
  }

  @Test
  void malformedJsonRetriesThreeTimesThenPublishesToDlt() {
    NotificationConsumerFixture f = new NotificationConsumerFixture();
    KafkaNotificationListener listener = new KafkaNotificationListener(f.codec, f.handler, observation.metrics);
    List<ProducerRecord<Object, Object>> published = new ArrayList<>();
    KafkaOperations<Object, Object> broker = broker(published);
    DefaultErrorHandler errors = configuration.notificationErrorHandler(configuration.notificationDeadLetterRecoverer(broker, new KafkaEventProperties(), observation.provider()));
    var record = KafkaNotificationListenerTest.record("private-event-content { malformed");
    try {
      for (int attempt = 1; attempt <= 3; attempt++) {
        Exception failure = (Exception) catchThrowable(() -> listener.onMessage(record));
        assertThat(failure).hasMessage("INVALID_EVENT_CONTRACT");
        assertThat(errors.handleOne(failure, record, null, null)).isEqualTo(attempt == 3);
        assertThat(published).hasSize(attempt == 3 ? 1 : 0);
      }
      assertThat(published.get(0).topic()).isEqualTo("volunteerflow.registration-events.v1.DLT");
      assertThat(published.get(0).value()).isEqualTo(record.value());
      assertThat(f.markers).isEmpty();
      assertThat(f.rows).isEmpty();
      assertThat(observation.registry.get("kafka.consumer.dlt").counter().count()).isEqualTo(1);
    } finally { errors.clearThreadState(); }
  }

  @Test
  void dltHeadersContainOnlySafeOriginMetadata() {
    List<ProducerRecord<Object, Object>> published = new ArrayList<>();
    DeadLetterPublishingRecoverer recoverer = configuration.notificationDeadLetterRecoverer(broker(published), new KafkaEventProperties(), observation.provider());
    var record = KafkaNotificationListenerTest.record("private-event-content");
    record.headers().add("Authorization", bytes("secret-token"));
    record.headers().add("kafka_dlt-exception-stacktrace", bytes("RAW_STACK password=SECRET"));
    record.headers().add("springDeserializerExceptionValue", bytes("untrusted-serialized-exception"));
    recoverer.accept(record, null, new IllegalStateException("RAW_EXCEPTION password=SECRET private-event-content"));
    var result = published.get(0);
    assertThat(result.partition()).isEqualTo(2);
    assertThat(result.key()).isEqualTo("1001:3001");
    assertThat(result.value()).isEqualTo("private-event-content");
    assertThat(result.headers()).extracting(Header::key).containsExactlyInAnyOrder(
        "source-topic", "source-partition", "source-offset", "error-category");
    assertThat(text(result, "source-topic")).isEqualTo("volunteerflow.registration-events.v1");
    assertThat(text(result, "source-partition")).isEqualTo("2");
    assertThat(text(result, "source-offset")).isEqualTo("41");
    assertThat(text(result, "error-category")).isEqualTo("NOTIFICATION_PROCESSING_FAILED");
    for (Header header : result.headers()) {
      assertThat(new String(header.value(), StandardCharsets.UTF_8)).doesNotContain("SECRET", "RAW_", "private-event-content", "secret-token");
    }
  }

  @Test
  void failedDltSendIsNotRecoveredAndLogsNoRawExceptionPayloadOrStack() {
    List<ProducerRecord<Object, Object>> published = new ArrayList<>();
    KafkaOperations<Object, Object> broker = broker(published);
    when(broker.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
        new IllegalStateException("RAW_BROKER_EXCEPTION password=SECRET private-event-content")));
    DefaultErrorHandler errors = configuration.notificationErrorHandler(configuration.notificationDeadLetterRecoverer(broker, new KafkaEventProperties(), observation.provider()));
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> capture = new ListAppender<>(); capture.start(); root.addAppender(capture);
    var record = KafkaNotificationListenerTest.record("private-event-content");
    try {
      for (int attempt = 0; attempt < 3; attempt++) {
        assertThat(errors.handleOne(new IllegalStateException("RAW_DB_ERROR password=SECRET"), record, null, null)).isFalse();
      }
      assertThat(capture.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("DLT_PUBLISH_FAILED"));
      assertThat(observation.registry.get("kafka.consumer.dlt").counter().count()).isZero();
      assertThat(capture.list).allSatisfy(event -> {
        assertThat(event.getFormattedMessage()).doesNotContain("RAW_", "SECRET", "private-event-content");
        if (event.getThrowableProxy() != null) {
          assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isEmpty();
          assertThat(event.getThrowableProxy().getCause()).isNull();
          assertThat(event.getThrowableProxy().getMessage()).doesNotContain("RAW_", "SECRET", "private-event-content");
        }
      });
    } finally { root.detachAppender(capture); capture.stop(); errors.clearThreadState(); }
  }

  @SuppressWarnings("unchecked")
  private static KafkaOperations<Object, Object> broker(List<ProducerRecord<Object, Object>> published) {
    KafkaOperations<Object, Object> broker = mock(KafkaOperations.class);
    when(broker.send(any(ProducerRecord.class))).thenAnswer(call -> {
      ProducerRecord<Object, Object> row = call.getArgument(0); published.add(row);
      return CompletableFuture.completedFuture(new SendResult<>(row,
          new RecordMetadata(new TopicPartition(row.topic(), 2), 0L, 42, 0L, 1, 1)));
    });
    return broker;
  }

  private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
  private static String text(ProducerRecord<?, ?> row, String key) {
    return new String(row.headers().lastHeader(key).value(), StandardCharsets.UTF_8);
  }
}
