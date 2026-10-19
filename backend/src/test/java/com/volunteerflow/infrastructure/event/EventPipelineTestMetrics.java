package com.volunteerflow.infrastructure.event;

import static org.mockito.Mockito.mock;

import com.volunteerflow.registration.PromotionReconcileTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** Real observations; only the external database gauge boundary is replaced. */
public class EventPipelineTestMetrics {
  public final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  public final EventPipelineMetrics metrics = new EventPipelineMetrics(registry, mock(OutboxEventMapper.class),
      mock(PromotionReconcileTaskMapper.class), Clock.systemUTC());
  public ObjectProvider<EventPipelineMetrics> provider() {
    return new StaticListableBeanFactory(Map.of("metrics", metrics)).getBeanProvider(EventPipelineMetrics.class);
  }
}
