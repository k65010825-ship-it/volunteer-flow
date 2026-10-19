package com.volunteerflow.infrastructure.event;

import java.time.*;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class OutboxRelayService {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
  private final OutboxClaimService claims;
  private final OutboxStateService states;
  private final KafkaEventGateway gateway;
  private final DomainEventCodec codec;
  private final EventTopicRouter topics;
  private final KafkaEventProperties properties;
  private final Clock clock;

  public OutboxRelayService(OutboxClaimService claims, OutboxStateService states, KafkaEventGateway gateway,
      DomainEventCodec codec, EventTopicRouter topics, KafkaEventProperties properties, Clock clock) {
    this.claims = claims; this.states = states; this.gateway = gateway; this.codec = codec;
    this.topics = topics; this.properties = properties; this.clock = clock;
  }

  @Transactional(propagation = Propagation.NEVER)
  public int publishBatch(String workerId) {
    int published = 0;
    for (OutboxEvent row : claims.claimBatch(workerId)) {
      // A sequential batch can outlive its lease while the broker is down; leave unsent rows reclaimable.
      if (!row.getLockedUntil().isAfter(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))) continue;
      try {
        try { validateStoredEvent(row); }
        catch (IllegalArgumentException invalid) { states.markDead(row, workerId); continue; }

        KafkaPublishReceipt receipt;
        try {
          receipt = gateway.publish(row.getTopic(), row.getMessageKey(), row.getPayloadJson())
              .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          // Unknown send outcome: lease expiry recovers this and all unprocessed rows.
          break;
        } catch (Exception brokerFailure) {
          states.markRetry(row, workerId);
          log.warn("Outbox publish failed; eventId={} topic={} category=BROKER_PUBLISH_FAILED", row.getEventId(), row.getTopic());
          continue;
        }
        // Intentionally outside the broker-failure catch. A DB failure must NOT rewrite an acknowledged send as RETRY.
        if (states.markPublished(row, workerId, receipt)) published++;
      } catch (RuntimeException stateFailure) {
        log.warn("Outbox state update failed; rowId={} category=STATE_UPDATE_FAILED", row.getId());
        // PROCESSING remains recoverable; never leak arbitrary exception messages/payloads.
      }
    }
    return published;
  }

  private void validateStoredEvent(OutboxEvent row) {
    DomainEventEnvelope event = codec.decode(row.getPayloadJson());
    if (!Objects.equals(row.getEventId(), event.eventId().toString())
        || !Objects.equals(row.getEventType(), event.eventType())
        || !Objects.equals(row.getOrganizationId(), event.organizationId())
        || !Objects.equals(row.getAggregateType(), event.aggregateType())
        || !Objects.equals(row.getAggregateId(), event.aggregateId())
        || !Objects.equals(row.getTopic(), topics.topicFor(event.eventType()))
        || !Objects.equals(row.getMessageKey(), topics.messageKey(event.organizationId(), event.activityId()))) {
      throw new IllegalArgumentException("Stored event metadata/routing mismatch");
    }
  }
}
