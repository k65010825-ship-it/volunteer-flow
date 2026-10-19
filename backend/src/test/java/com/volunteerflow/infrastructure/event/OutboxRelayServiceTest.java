package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OutboxRelayServiceTest {
  private final RelayFixture f = new RelayFixture();

  private OutboxRelayService relay(KafkaEventGateway gateway) {
    return f.advised(new OutboxRelayService(f.claims(), new OutboxStateService(f.mapper, f.clock, f.properties),
        gateway, new DomainEventCodec(JsonMapper.builder().findAndAddModules().build()),
        new EventTopicRouter("volunteerflow.activity-events.v1", "volunteerflow.registration-events.v1", "volunteerflow.checkin-events.v1"),
        f.properties, f.clock));
  }

  @Test
  void publishesOutsideTheClaimTransaction() {
    f.row(1, "PENDING");
    assertThat(relay((topic, key, json) -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      assertThat(f.transactions.commits).isEqualTo(1);
      assertThat(topic).isEqualTo("volunteerflow.registration-events.v1"); assertThat(key).isEqualTo("1001:3001");
      assertThat(json).isEqualTo(f.rows.get(1L).getPayloadJson());
      return CompletableFuture.completedFuture(new KafkaPublishReceipt(3, 42));
    }).publishBatch("worker")).isEqualTo(1);
  }

  @Test
  void recordsPartitionAndOffsetForTheCurrentOwner() {
    OutboxEvent row = f.row(1, "PENDING");
    assertThat(relay(success()).publishBatch("worker")).isEqualTo(1);
    assertThat(row.getStatus()).isEqualTo("PUBLISHED");
    assertThat(row.getPublishedPartition()).isEqualTo(3); assertThat(row.getPublishedOffset()).isEqualTo(42);
    assertThat(row.getPublishedAt()).isEqualTo(f.now()); assertThat(row.getLockedBy()).isNull();
  }

  @Test
  void publishSuccessWithStateUpdateFailureLeavesRowReclaimable() {
    OutboxEvent row = f.row(1, "PENDING"); f.failPublished = true;
    assertThat(relay(success()).publishBatch("worker")).isZero();
    assertThat(row.getStatus()).isEqualTo("PROCESSING"); assertThat(row.getRetryCount()).isZero();
    assertThat(row.getLockedUntil()).isEqualTo(f.now().plusSeconds(60));
    Clock later = Clock.offset(f.clock, Duration.ofSeconds(61));
    OutboxClaimService recovery = f.advised(new OutboxClaimService(f.mapper, f.properties, later));
    assertThat(recovery.claimBatch("recovery")).extracting(OutboxEvent::getId).containsExactly(1L);
    assertThat(row.getLockedBy()).isEqualTo("recovery");
  }

  @Test
  void retriesBrokerFailuresWithoutMarkingDead() {
    OutboxEvent row = f.row(1, "PENDING"); row.setRetryCount(1000);
    assertThat(relay((t, k, j) -> CompletableFuture.failedFuture(new IllegalArgumentException("password=secret payload contact")))
        .publishBatch("worker")).isZero();
    assertThat(row.getStatus()).isEqualTo("RETRY"); assertThat(row.getRetryCount()).isEqualTo(1001);
    assertThat(row.getNextRetryAt()).isBetween(f.now().plusSeconds(240), f.now().plusSeconds(300));
    assertThat(row.getLastError()).isEqualTo("BROKER_PUBLISH_FAILED");
  }

  @Test
  void marksPermanentContractFailureDead() {
    for (String invalid : new String[]{"not-json", f.row(9, "PENDING").getPayloadJson().replace(".v1", ".v2")}) {
      f.rows.clear(); OutboxEvent row = f.row(1, "PENDING"); row.setPayloadJson(invalid);
      assertThat(relay((t, k, j) -> { throw new AssertionError("Invalid contract reached Kafka"); }).publishBatch("worker")).isZero();
      assertThat(row.getStatus()).isEqualTo("DEAD"); assertThat(row.getLastError()).isEqualTo("INVALID_EVENT_CONTRACT");
    }
  }

  @Test
  void rejectsMismatchedStoredRoutingBeforeBrokerIo() {
    OutboxEvent row = f.row(1, "PENDING"); row.setMessageKey("wrong-key");
    relay((t, k, j) -> { throw new AssertionError("Invalid routing reached Kafka"); }).publishBatch("worker");
    assertThat(row.getStatus()).isEqualTo("DEAD");
  }

  @Test
  void timesOutUnacknowledgedSendAndContinuesTheBatch() {
    f.properties.setSendTimeout(Duration.ofMillis(5)); f.row(1, "PENDING"); f.row(2, "PENDING");
    AtomicInteger calls = new AtomicInteger();
    assertThat(relay((t, k, j) -> calls.incrementAndGet() == 1 ? new CompletableFuture<>()
        : CompletableFuture.completedFuture(new KafkaPublishReceipt(3, 42))).publishBatch("worker")).isEqualTo(1);
    assertThat(f.rows.get(1L).getStatus()).isEqualTo("RETRY"); assertThat(f.rows.get(2L).getStatus()).isEqualTo("PUBLISHED");
  }

  @Test
  void retryDelayHasBoundedJitterAtInitialAndMaximumAttempts() {
    OutboxStateService states = new OutboxStateService(f.mapper, f.clock, f.properties);
    for (int attempt : new int[]{0, 1, 5, 1000}) {
      OutboxEvent row = f.row(1, "PROCESSING"); row.setLockedBy("worker"); row.setRetryCount(attempt);
      assertThat(states.markRetry(row, "worker")).isTrue();
      long cap = switch (attempt) { case 0 -> 5; case 1 -> 10; case 5 -> 160; default -> 300; };
      assertThat(Duration.between(f.now(), row.getNextRetryAt()).toMillis()).isBetween(cap * 800, cap * 1000);
    }
  }

  private KafkaEventGateway success() { return (t, k, j) -> CompletableFuture.completedFuture(new KafkaPublishReceipt(3, 42)); }
}
