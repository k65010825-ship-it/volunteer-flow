package com.volunteerflow.infrastructure.event;

import java.util.concurrent.CompletableFuture;

public interface KafkaEventGateway {
  CompletableFuture<KafkaPublishReceipt> publish(String topic, String key, String json);
}
