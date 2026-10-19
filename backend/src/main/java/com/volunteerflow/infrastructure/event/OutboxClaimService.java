package com.volunteerflow.infrastructure.event;

import java.time.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class OutboxClaimService {
  private final OutboxEventMapper outbox;
  private final KafkaEventProperties properties;
  private final Clock clock;

  public OutboxClaimService(OutboxEventMapper outbox, KafkaEventProperties properties, Clock clock) {
    this.outbox = outbox; this.properties = properties; this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<OutboxEvent> claimBatch(String workerId) {
    if (workerId == null || workerId.isBlank() || workerId.length() > 128) {
      throw new IllegalArgumentException("Invalid outbox lease owner");
    }
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    List<OutboxEvent> rows = outbox.selectClaimableForUpdate(now, properties.getBatchSize());
    if (rows.isEmpty()) return rows;
    LocalDateTime until = now.plus(properties.getLease());
    if (outbox.claim(rows.stream().map(OutboxEvent::getId).toList(), workerId, until, now) != rows.size()) {
      throw new IllegalStateException("Outbox claim changed under row lock");
    }
    rows.forEach(row -> { row.setStatus("PROCESSING"); row.setLockedBy(workerId); row.setLockedUntil(until); });
    return rows;
  }
}
