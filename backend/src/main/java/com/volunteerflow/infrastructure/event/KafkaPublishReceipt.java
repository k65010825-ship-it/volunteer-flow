package com.volunteerflow.infrastructure.event;

public record KafkaPublishReceipt(int partition, long offset) {}
