package com.volunteerflow.infrastructure.event;

import java.time.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Single owner-guarded SQL writes; no open claim transaction or broker calls. */
@Service
@Profile("!test")
public class OutboxStateService {
  private final OutboxEventMapper outbox;
  private final Clock clock;
  private final KafkaEventProperties properties;

  public OutboxStateService(OutboxEventMapper outbox, Clock clock, KafkaEventProperties properties) {
    this.outbox = outbox; this.clock = clock; this.properties = properties;
  }

  public boolean markPublished(OutboxEvent row, String owner, KafkaPublishReceipt receipt) {
    return outbox.markPublished(row.getId(), owner, receipt.partition(), receipt.offset(), now()) == 1;
  }

  public boolean markRetry(OutboxEvent row, String owner) {
    int previous = row.getRetryCount() == null ? 0 : Math.max(0, row.getRetryCount());
    long cap = properties.getRetryInitial().toMillis();
    long max = properties.getRetryMax().toMillis();
    for (int i = 0; i < Math.min(previous, 63) && cap < max; i++) {
      cap = cap > max / 2 ? max : Math.min(max, cap * 2);
    }
    cap = Math.min(cap, max);
    long lower = Math.max(1, cap - cap / 5);
    long delay = ThreadLocalRandom.current().nextLong(lower, cap + 1);
    return outbox.markRetry(row.getId(), owner, previous == Integer.MAX_VALUE ? previous : previous + 1,
        now().plusNanos(delay * 1_000_000), "BROKER_PUBLISH_FAILED") == 1;
  }

  public boolean markDead(OutboxEvent row, String owner) {
    // Fixed safe categories only: never persist exception text, broker credentials, or payload.
    return outbox.markDead(row.getId(), owner, "INVALID_EVENT_CONTRACT") == 1;
  }

  private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
