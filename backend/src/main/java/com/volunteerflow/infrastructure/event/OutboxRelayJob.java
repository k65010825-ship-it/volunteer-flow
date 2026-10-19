package com.volunteerflow.infrastructure.event;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class OutboxRelayJob {
  private static final Logger log = LoggerFactory.getLogger(OutboxRelayJob.class);
  private final OutboxRelayService relay;

  public OutboxRelayJob(OutboxRelayService relay) { this.relay = relay; }

  @Scheduled(fixedDelayString = "${volunteerflow.events.poll-interval:1s}", scheduler = "outboxRelayScheduler")
  public int publishBatch() {
    try {
      // Fresh owner on every claim prevents same-process ABA after lease recovery.
      return relay.publishBatch(UUID.randomUUID().toString());
    } catch (RuntimeException failure) {
      log.warn("Outbox scan failed category=CLAIM_FAILED");
      return 0;
    }
  }
}
