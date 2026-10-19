package com.volunteerflow.infrastructure.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Uses configured Jackson modules/ID serializers without Java-class wire discriminators. */
@Component
public class DomainEventCodec {
  private final ObjectMapper objectMapper;

  public DomainEventCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String encode(DomainEventEnvelope event) {
    if (event == null) {
      throw new IllegalArgumentException("Event is required");
    }
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      // Do not copy parser messages or payload data to the externally logged exception.
      throw new IllegalArgumentException("Cannot encode domain event");
    }
  }

  public DomainEventEnvelope decode(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("Event JSON is required");
    }
    try {
      WireEnvelope wire = objectMapper.readerFor(WireEnvelope.class)
          .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
              DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .readValue(json);
      if (wire == null || wire.payload() == null || !wire.payload().isObject()) {
        throw new IllegalArgumentException("Event payload is required");
      }
      DomainEventPayload payload = objectMapper.readerFor(DomainEventTypes.payloadType(wire.eventType()))
          .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(wire.payload());
      return new DomainEventEnvelope(wire.eventId(), wire.eventType(), wire.occurredAt(),
          wire.organizationId(), wire.aggregateType(), wire.aggregateId(), wire.activityId(),
          wire.requestId(), payload);
    } catch (IOException exception) {
      throw new IllegalArgumentException("Invalid domain event JSON/contract");
    }
  }

  private record WireEnvelope(UUID eventId, String eventType, Instant occurredAt,
      Long organizationId, String aggregateType, String aggregateId, Long activityId,
      String requestId, JsonNode payload) {}
}
