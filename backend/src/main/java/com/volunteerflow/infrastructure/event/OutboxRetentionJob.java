package com.volunteerflow.infrastructure.event;

import java.time.*;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class OutboxRetentionJob {
  private static final Logger log = LoggerFactory.getLogger(OutboxRetentionJob.class);
  private final OutboxEventMapper outbox;
  private final KafkaEventProperties properties;
  private final Clock clock;

  public OutboxRetentionJob(OutboxEventMapper outbox, KafkaEventProperties properties, Clock clock) {
    this.outbox = outbox; this.properties = properties; this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${volunteerflow.events.retention-interval:1h}", scheduler = "taskScheduler")
  public int purgeBatch() {
    try {
      LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant().minus(properties.getRetention()), ZoneOffset.UTC);
      List<Long> ids = outbox.selectPublishedIdsBefore(cutoff, null, null, properties.getRetentionBatchSize());
      // One bounded keyset page per tick; deletion itself advances the first-page cursor.
      return ids.isEmpty() ? 0 : outbox.deletePublishedByIds(ids);
    } catch (RuntimeException failure) {
      // Do not let Spring's default scheduler error handler log raw DB messages or stack traces.
      // The next retention tick retries the remaining eligible rows.
      log.warn("Outbox retention failed category=RETENTION_FAILED");
      return 0;
    }
  }
}
