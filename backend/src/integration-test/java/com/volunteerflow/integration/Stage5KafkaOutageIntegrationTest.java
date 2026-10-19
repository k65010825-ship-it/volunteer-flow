package com.volunteerflow.integration;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Stage5KafkaOutageIntegrationTest extends Stage5IntegrationSupport {
  @Test
  void sameBrokerContainerRestartsAndRecoversCommittedRegistrationExactlyOnce() throws Exception {
    drain(eventId(submit(101L).registrationId()));
    String containerId = broker.getContainerId();
    var docker = broker.getDockerClient();
    docker.stopContainerCmd(containerId).withTimeout(10).exec();
    try {
      var result = submit(102L);
      assertThat(result.status()).isEqualTo("CONFIRMED");
      String eventId = eventId(result.registrationId());
      assertThat(count("audit_log", "actor_user_id=102 AND action='registration.submitted'")).isEqualTo(1);
      assertThat(count("registration_cycle", "user_id=102 AND status='CONFIRMED'")).isEqualTo(1);
      assertThat(count("outbox_event", "event_id='" + eventId + "' AND status='PENDING'")).isEqualTo(1);
      relay.publishBatch("outage-owner");
      assertThat(count("outbox_event", "event_id='" + eventId + "' AND status='RETRY'")).isEqualTo(1);
      assertThat(count("notification", "user_id=102")).isZero();
      assertThat(counter("outbox.publish.failure")).isPositive();
      assertThat(pipeline.health().getStatus().getCode()).isEqualTo("DOWN");
      assertCoreReadiness();
      docker.startContainerCmd(containerId).exec();
      waitForBroker();
      assertThat(broker.getContainerId()).isEqualTo(containerId);
      drain(eventId);
      var row = outbox.selectById(outboxId(eventId));
      double duplicates = counter("kafka.consumer.duplicate");
      kafka.send(row.getTopic(), row.getMessageKey(), row.getPayloadJson()).get();
      waitForDuplicate(duplicates);
      assertThat(count("notification", "user_id=102")).isEqualTo(1);
      assertThat(count("consumed_event", "event_id='" + eventId + "'")).isEqualTo(1);
      assertThat(pipeline.health().getStatus().getCode()).isEqualTo("UP");
    } finally {
      if (!Boolean.TRUE.equals(docker.inspectContainerCmd(containerId).exec().getState().getRunning())) {
        docker.startContainerCmd(containerId).exec();
        waitForBroker();
      }
    }
  }
}
