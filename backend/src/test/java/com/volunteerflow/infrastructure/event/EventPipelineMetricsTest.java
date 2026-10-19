package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.volunteerflow.registration.PromotionReconcileTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class EventPipelineMetricsTest {
  @Test
  void unavailableDatabaseDoesNotLeakRawExceptionsThroughGaugeLogging() {
    var mapper = mock(OutboxEventMapper.class);
    when(mapper.countPending()).thenThrow(new IllegalStateException("password=SECRET private-event-content"));
    var registry = new SimpleMeterRegistry();
    new EventPipelineMetrics(registry, mapper, mock(PromotionReconcileTaskMapper.class), Clock.systemUTC());
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); root.addAppender(logs);
    try {
      assertThat(registry.get("outbox.pending.count").gauge().value()).isNaN();
      assertThat(logs.list).allSatisfy(event -> {
        assertThat(event.getFormattedMessage()).doesNotContain("SECRET", "private-event-content");
        assertThat(event.getThrowableProxy()).isNull();
      });
    } finally { root.detachAppender(logs); logs.stop(); }
  }

  @Test
  void exposesDatabaseBacklogAndAgeRatherThanProcessLocalQueueSize() {
    var outbox = mock(OutboxEventMapper.class);
    var tasks = mock(PromotionReconcileTaskMapper.class);
    when(outbox.countPending()).thenReturn(7L);
    when(outbox.countDead()).thenReturn(2L);
    when(outbox.oldestPendingCreatedAt()).thenReturn(LocalDateTime.parse("2026-10-18T14:29:00"));
    when(tasks.countPending()).thenReturn(3L);
    var registry = new SimpleMeterRegistry();
    new EventPipelineMetrics(registry, outbox, tasks,
        Clock.fixed(Instant.parse("2026-10-18T14:30:00Z"), ZoneOffset.UTC));
    assertThat(registry.get("outbox.pending.count").gauge().value()).isEqualTo(7);
    assertThat(registry.get("outbox.dead.count").gauge().value()).isEqualTo(2);
    assertThat(registry.get("outbox.oldest.pending.age").gauge().value()).isEqualTo(60);
    assertThat(registry.get("promotion.reconcile.pending").gauge().value()).isEqualTo(3);
    when(outbox.oldestPendingCreatedAt()).thenReturn(null);
    assertThat(registry.get("outbox.oldest.pending.age").gauge().value()).isZero();
    for (String name : new String[]{"outbox.publish.success", "outbox.publish.failure", "kafka.consumer.success",
        "kafka.consumer.duplicate", "kafka.consumer.dlt", "promotion.reconcile.success", "promotion.reconcile.failure"}) {
      assertThat(registry.get(name).counter().count()).isZero();
    }
  }

  @Test
  void countsRealRelayOutcomesAndDoesNotCountLostOwnerAsPublished() {
    RelayFixture f = new RelayFixture();
    var registry = new SimpleMeterRegistry();
    var metrics = new EventPipelineMetrics(registry, f.mapper, mock(PromotionReconcileTaskMapper.class), f.clock);
    f.row(1, "PENDING");
    relay(f, metrics, false).publishBatch("owner1");
    assertThat(registry.get("outbox.publish.failure").counter().count()).isEqualTo(1);
    assertThat(registry.get("outbox.publish.success").counter().count()).isZero();
    f.rows.clear(); f.row(2, "PENDING");
    relay(f, metrics, true).publishBatch("owner2");
    assertThat(registry.get("outbox.publish.success").counter().count()).isEqualTo(1);
    f.rows.clear(); f.row(3, "PENDING"); f.failPublished = true;
    relay(f, metrics, true).publishBatch("owner3");
    assertThat(registry.get("outbox.publish.success").counter().count()).isEqualTo(1);
  }

  private OutboxRelayService relay(RelayFixture f, EventPipelineMetrics metrics, boolean success) {
    return f.advised(new OutboxRelayService(f.claims(), new OutboxStateService(f.mapper, f.clock, f.properties),
        (t, k, j) -> success ? CompletableFuture.completedFuture(new KafkaPublishReceipt(0, 5))
            : CompletableFuture.failedFuture(new IllegalStateException("untrusted secret")),
        new DomainEventCodec(JsonMapper.builder().findAndAddModules().build()),
        new EventTopicRouter("volunteerflow.activity-events.v1", "volunteerflow.registration-events.v1", "volunteerflow.checkin-events.v1"),
        f.properties, f.clock, metrics));
  }
}
