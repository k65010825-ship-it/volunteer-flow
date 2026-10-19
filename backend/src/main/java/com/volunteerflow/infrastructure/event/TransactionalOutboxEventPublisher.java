package com.volunteerflow.infrastructure.event;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.infrastructure.web.RequestIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionalOutboxEventPublisher implements DomainEventPublisher {
  private final OutboxEventMapper outbox;
  private final DomainEventCodec codec;
  private final EventTopicRouter topics;
  private final Clock clock;

  public TransactionalOutboxEventPublisher(OutboxEventMapper outbox, DomainEventCodec codec,
      EventTopicRouter topics, Clock clock) {
    this.outbox = outbox;
    this.codec = codec;
    this.topics = topics;
    this.clock = clock;
  }

  @Override
  public UUID publish(DomainEventCommand command) {
    Objects.requireNonNull(command, "command");
    UUID eventId = UUID.randomUUID();
    Instant occurredAt = clock.instant();
    DomainEventEnvelope event = new DomainEventEnvelope(eventId, command.eventType(), occurredAt,
        command.organizationId(), command.aggregateType(), command.aggregateId(), command.activityId(),
        MDC.get(RequestIdFilter.MDC_KEY), command.payload());
    LocalDateTime utcTime = LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC);
    OutboxEvent row = new OutboxEvent();
    row.setId(IdWorker.getId());
    row.setOrganizationId(event.organizationId());
    row.setEventId(eventId.toString());
    row.setAggregateType(event.aggregateType());
    row.setAggregateId(event.aggregateId());
    row.setEventType(event.eventType());
    row.setPayloadJson(codec.encode(event));
    row.setTopic(topics.topicFor(event.eventType()));
    row.setMessageKey(topics.messageKey(event.organizationId(), event.activityId()));
    row.setStatus("PENDING");
    row.setRetryCount(0);
    row.setNextRetryAt(utcTime);
    row.setCreateTime(utcTime);
    row.setUpdateTime(utcTime);
    outbox.insert(row);
    return eventId;
  }

  @Override
  public int publishMany(Collection<Long> userIds, NotificationEventTemplate template) {
    Objects.requireNonNull(userIds, "userIds");
    Objects.requireNonNull(template, "template");
    // Validate all recipients first, so malformed input cannot leave a partial fan-out.
    List<DomainEventCommand> commands = new LinkedHashSet<>(userIds).stream()
        .map(template::forUser).toList();
    commands.forEach(this::publish);
    return commands.size();
  }
}
