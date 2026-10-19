package com.volunteerflow.infrastructure.event;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KafkaEventProperties.class)
public class KafkaEventConfiguration {
  @Bean
  ThreadPoolTaskScheduler outboxRelayScheduler() {
    return scheduler("outbox-relay-");
  }

  @Bean
  ThreadPoolTaskScheduler taskScheduler() {
    // Preserve the existing single-thread maintenance behavior on a separate default executor.
    // Naming it prevents Spring from choosing the relay executor as the unique default scheduler.
    return scheduler("volunteerflow-maintenance-");
  }

  private static ThreadPoolTaskScheduler scheduler(String prefix) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix(prefix);
    scheduler.setRemoveOnCancelPolicy(true);
    return scheduler;
  }

  @Bean
  ProducerFactory<String, String> eventProducerFactory(KafkaEventProperties properties) {
    Map<String, Object> config = new HashMap<>();
    config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers());
    config.put(ProducerConfig.CLIENT_ID_CONFIG, properties.getClientId());
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    config.put(ProducerConfig.ACKS_CONFIG, "all");
    config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, properties.getSendTimeout().toMillis());
    config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) properties.getSendTimeout().toMillis());
    config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) properties.getSendTimeout().toMillis());
    config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
    return new DefaultKafkaProducerFactory<>(config);
  }

  @Bean
  KafkaTemplate<String, String> eventKafkaTemplate(ProducerFactory<String, String> factory) {
    KafkaTemplate<String, String> template = new KafkaTemplate<>(factory);
    // The default LoggingProducerListener prints the entire record and raw exception on failure.
    // Relay owns safe categorized failure logging; never attach the payload-logging listener.
    template.setProducerListener(new ProducerListener<>() {});
    return template;
  }

  @Bean
  KafkaAdmin eventKafkaAdmin(KafkaEventProperties properties) {
    KafkaAdmin admin = new KafkaAdmin(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.getBootstrapServers()));
    admin.setFatalIfBrokerNotAvailable(false);
    admin.setAutoCreate(false); // Deployment owns topics; startup never probes the broker.
    return admin;
  }

  @Bean
  KafkaEventGateway kafkaEventGateway(KafkaTemplate<String, String> template) {
    return new SpringKafkaEventGateway(template);
  }
}
