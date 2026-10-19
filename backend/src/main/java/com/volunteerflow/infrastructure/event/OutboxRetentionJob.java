package com.volunteerflow.infrastructure.event;

import java.time.*;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class OutboxRetentionJob {
  private final OutboxEventMapper outbox;
  private final KafkaEventProperties properties;
  private final Clock clock;

  public OutboxRetentionJob(OutboxEventMapper outbox, KafkaEventProperties properties, Clock clock) {
    this.outbox = outbox; this.properties = properties; this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${volunteerflow.events.retention-interval:1h}", scheduler = "taskScheduler")
  public int purgeBatch() {
    LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant().minus(properties.getRetention()), ZoneOffset.UTC);
    List<Long> ids = outbox.selectPublishedIdsBefore(cutoff, null, null, properties.getRetentionBatchSize());
    // One bounded keyset page per tick; deletion itself advances the first-page cursor.
    return ids.isEmpty() ? 0 : outbox.deletePublishedByIds(ids);
  }
}
