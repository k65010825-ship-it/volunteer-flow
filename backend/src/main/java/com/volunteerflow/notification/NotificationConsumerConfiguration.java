package com.volunteerflow.notification;

import com.volunteerflow.infrastructure.event.KafkaEventProperties;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@EnableKafka
public class NotificationConsumerConfiguration {
  private static final Logger LOG = LoggerFactory.getLogger(NotificationConsumerConfiguration.class);
  @Bean
  ConsumerFactory<String, String> notificationConsumerFactory(KafkaEventProperties properties) {
    Map<String, Object> config = new HashMap<>();
    config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
    config.put(ConsumerConfig.CLIENT_ID_CONFIG, properties.getClientId() + "-notification");
    config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getConsumerGroup());
    config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    config.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
    return new DefaultKafkaConsumerFactory<>(config);
  }

  @Bean
  ConcurrentKafkaListenerContainerFactory<String, String> notificationKafkaListenerContainerFactory(
      @Qualifier("notificationConsumerFactory") ConsumerFactory<String, String> consumers,
      DefaultErrorHandler notificationErrorHandler) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumers);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
    factory.getContainerProperties().setMissingTopicsFatal(false);
    factory.setCommonErrorHandler(notificationErrorHandler);
    return factory;
  }

  @Bean
  DefaultErrorHandler notificationErrorHandler(DeadLetterPublishingRecoverer notificationDeadLetterRecoverer) {
    DefaultErrorHandler errors = new DefaultErrorHandler(notificationDeadLetterRecoverer, new FixedBackOff(0L, 2L)) {
      @Override
      public boolean handleOne(Exception failure, ConsumerRecord<?, ?> record, Consumer<?, ?> consumer,
          MessageListenerContainer container) {
        // Spring's failure logger also formats the input exception; pass only our fixed categories.
        return super.handleOne(safeFailure(failure), record, consumer, container);
      }
    };
    // Retain the failed record in the container instead of throwing seek exceptions into its logger.
    // The container pauses this partition and retries it before later records, preserving ordering.
    errors.setSeekAfterError(false);
    errors.setResetStateOnExceptionChange(false);
    errors.setAckAfterHandle(true); // Only a confirmed DLT publication is a recovered success.
    return errors;
  }

  @Bean
  DeadLetterPublishingRecoverer notificationDeadLetterRecoverer(KafkaOperations<?, ?> template,
      KafkaEventProperties properties) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
        (record, failure) -> new TopicPartition(record.topic() + ".DLT", record.partition())) {
      @Override
      public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception failure) {
        // Do not deserialize or carry untrusted incoming headers, including serialized exceptions.
        ConsumerRecord<Object, Object> clean = new ConsumerRecord<>(record.topic(), record.partition(),
            record.offset(), record.key(), record.value());
        super.accept(clean, consumer, safeFailure(failure));
      }

      @Override
      protected ProducerRecord<Object, Object> createProducerRecord(ConsumerRecord<?, ?> record,
          TopicPartition destination, Headers ignored, byte[] key, byte[] value) {
        Headers safe = new RecordHeaders();
        header(safe, "source-topic", record.topic());
        header(safe, "source-partition", Integer.toString(record.partition()));
        header(safe, "source-offset", Long.toString(record.offset()));
        // Set by the headers function from a sanitized failure, never an exception message.
        safe.add(ignored.lastHeader("error-category"));
        return new ProducerRecord<>(destination.topic(), destination.partition(), record.key(), record.value(), safe);
      }

      @Override
      protected void publish(ProducerRecord<Object, Object> output, KafkaOperations<Object, Object> operations,
          ConsumerRecord<?, ?> original) {
        // Default publish() logs full ProducerRecord + exception on failure; keep that path out.
        try {
          operations.send(output).get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          LOG.warn("Notification dead-letter publication failed category=DLT_PUBLISH_FAILED");
          throw new KafkaNotificationListener.ProcessingFailure("DLT_PUBLISH_FAILED");
        } catch (Exception failure) {
          LOG.warn("Notification dead-letter publication failed category=DLT_PUBLISH_FAILED");
          throw new KafkaNotificationListener.ProcessingFailure("DLT_PUBLISH_FAILED");
        }
      }
    };
    recoverer.setVerifyPartition(false);
    recoverer.setLogRecoveryRecord(false);
    recoverer.setExceptionHeadersCreator((headers, failure, isKey, names) -> {});
    recoverer.setHeadersFunction((record, failure) -> {
      Headers headers = new RecordHeaders();
      header(headers, "error-category", safeFailure(failure).getMessage());
      return headers;
    });
    return recoverer;
  }

  private static KafkaNotificationListener.ProcessingFailure safeFailure(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof KafkaNotificationListener.ProcessingFailure safe) return safe;
    }
    return new KafkaNotificationListener.ProcessingFailure("NOTIFICATION_PROCESSING_FAILED");
  }

  private static void header(Headers headers, String name, String value) {
    headers.add(name, value.getBytes(StandardCharsets.UTF_8));
  }
}
