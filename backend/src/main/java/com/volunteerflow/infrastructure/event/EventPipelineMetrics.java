package com.volunteerflow.infrastructure.event;

import com.volunteerflow.registration.PromotionReconcileTaskMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Low-cardinality process counters and database-backed, cross-instance backlog gauges. */
@Component
@Profile("!test")
public class EventPipelineMetrics {
  private final Counter publishSuccess;
  private final Counter publishFailure;
  private final Counter consumerSuccess;
  private final Counter consumerDuplicate;
  private final Counter consumerDlt;
  private final Counter reconcileSuccess;
  private final Counter reconcileFailure;
  private final AtomicReference<PublishState> publishState = new AtomicReference<>(PublishState.UNKNOWN);

  public EventPipelineMetrics(MeterRegistry registry, OutboxEventMapper outbox,
      PromotionReconcileTaskMapper tasks, Clock clock) {
    Gauge.builder("outbox.pending.count", outbox, mapper -> sample(mapper::countPending)).register(registry);
    Gauge.builder("outbox.dead.count", outbox, mapper -> sample(mapper::countDead)).register(registry);
    Gauge.builder("outbox.oldest.pending.age", outbox, mapper -> sample(() -> {
      var oldest = mapper.oldestPendingCreatedAt();
      return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(ZoneOffset.UTC), clock.instant()).toSeconds());
    })).baseUnit("seconds").register(registry);
    Gauge.builder("promotion.reconcile.pending", tasks, mapper -> sample(mapper::countPending)).register(registry);
    publishSuccess = registry.counter("outbox.publish.success");
    publishFailure = registry.counter("outbox.publish.failure");
    consumerSuccess = registry.counter("kafka.consumer.success");
    consumerDuplicate = registry.counter("kafka.consumer.duplicate");
    consumerDlt = registry.counter("kafka.consumer.dlt");
    reconcileSuccess = registry.counter("promotion.reconcile.success");
    reconcileFailure = registry.counter("promotion.reconcile.failure");
  }

  public void publishSucceeded() { publishSuccess.increment(); publishState.set(PublishState.UP); }
  public void publishFailed() { publishFailure.increment(); publishState.set(PublishState.DOWN); }
  public void consumed(boolean duplicate) { if (duplicate) consumerDuplicate.increment(); else consumerSuccess.increment(); }
  public void deadLettered() { consumerDlt.increment(); }
  public void reconciled() { reconcileSuccess.increment(); }
  public void reconciliationFailed() { reconcileFailure.increment(); }
  public PublishState publishState() { return publishState.get(); }

  private static double sample(DoubleSupplier query) {
    try { return query.getAsDouble(); }
    catch (RuntimeException unavailable) {
      // NaN means unavailable, never an invented empty backlog. Micrometer must not log raw SQL exceptions.
      return Double.NaN;
    }
  }

  public enum PublishState { UNKNOWN, UP, DOWN }
}
