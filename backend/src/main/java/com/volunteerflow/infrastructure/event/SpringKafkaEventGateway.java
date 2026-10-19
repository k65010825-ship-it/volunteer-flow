package com.volunteerflow.infrastructure.event;

import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.core.KafkaTemplate;

public class SpringKafkaEventGateway implements KafkaEventGateway {
  private final KafkaTemplate<String, String> template;

  public SpringKafkaEventGateway(KafkaTemplate<String, String> template) {
    this.template = template;
  }

  @Override
  public CompletableFuture<KafkaPublishReceipt> publish(String topic, String key, String json) {
    return template.send(topic, key, json).thenApply(result -> new KafkaPublishReceipt(
        result.getRecordMetadata().partition(), result.getRecordMetadata().offset()));
  }
}
