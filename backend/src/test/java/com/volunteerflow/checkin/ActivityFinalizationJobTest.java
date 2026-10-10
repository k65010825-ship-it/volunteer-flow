package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.activity.ActivityMapper.DueActivityId;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class ActivityFinalizationJobTest {
  private final ActivityMapper activities = mock(ActivityMapper.class);
  private final ActivityFinalizationService finalizer = mock(ActivityFinalizationService.class);
  private final ActivityFinalizationProperties properties = new ActivityFinalizationProperties();
  private final LocalDateTime now = LocalDateTime.of(2026, 10, 10, 12, 0);
  private final LocalDateTime end = now.minusHours(1);
  private ActivityFinalizationJob job;

  @BeforeEach void setup() {
    properties.setBatchSize(2);
    properties.setMaxBatchesPerRun(3);
    job = new ActivityFinalizationJob(activities, finalizer, properties,
        Clock.fixed(now.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
  }

  @Test void repeatedEndTimesAdvanceByLastIdUntilEmptyPage() {
    when(activities.selectDueIds(now, null, null, 2))
        .thenReturn(List.of(new DueActivityId(1L, end), new DueActivityId(2L, end)));
    when(activities.selectDueIds(now, end, 2L, 2))
        .thenReturn(List.of(new DueActivityId(3L, end), new DueActivityId(4L, end.plusMinutes(1))));
    when(activities.selectDueIds(now, end.plusMinutes(1), 4L, 2)).thenReturn(List.of());
    when(finalizer.finalizeOne(anyLong(), eq(now))).thenReturn(true);

    assertThat(job.finalizeBatch()).isEqualTo(4);
    var order = inOrder(activities, finalizer);
    order.verify(activities).selectDueIds(now, null, null, 2);
    order.verify(finalizer).finalizeOne(1L, now);
    order.verify(finalizer).finalizeOne(2L, now);
    order.verify(activities).selectDueIds(now, end, 2L, 2);
    order.verify(finalizer).finalizeOne(3L, now);
    order.verify(finalizer).finalizeOne(4L, now);
    order.verify(activities).selectDueIds(now, end.plusMinutes(1), 4L, 2);
    verifyNoMoreInteractions(activities, finalizer);
  }

  @Test void fullPagesStopAtMaxBatchesWithoutCursorReset() {
    properties.setMaxBatchesPerRun(2);
    when(activities.selectDueIds(now, null, null, 2))
        .thenReturn(List.of(new DueActivityId(1L, end), new DueActivityId(2L, end)));
    when(activities.selectDueIds(now, end, 2L, 2))
        .thenReturn(List.of(new DueActivityId(3L, end), new DueActivityId(4L, end)));
    when(finalizer.finalizeOne(anyLong(), eq(now))).thenReturn(true);
    assertThat(job.finalizeBatch()).isEqualTo(4);
    verify(activities).selectDueIds(now, null, null, 2);
    verify(activities).selectDueIds(now, end, 2L, 2);
    verifyNoMoreInteractions(activities);
  }

  @Test void emptyFirstPageDoesNoFinalization() {
    when(activities.selectDueIds(now, null, null, 2)).thenReturn(List.of());
    assertThat(job.finalizeBatch()).isZero();
    verifyNoInteractions(finalizer);
    verify(activities).selectDueIds(now, null, null, 2);
    verifyNoMoreInteractions(activities);
  }

  @Test void failedLastIdStillAdvancesCursorAndLogsCorrelationOnly() {
    when(activities.selectDueIds(now, null, null, 2))
        .thenReturn(List.of(new DueActivityId(1L, end), new DueActivityId(2L, end)));
    when(activities.selectDueIds(now, end, 2L, 2)).thenReturn(List.of(new DueActivityId(3L, end)));
    when(finalizer.finalizeOne(1L, now)).thenReturn(true);
    when(finalizer.finalizeOne(2L, now)).thenThrow(new IllegalStateException("private-audit-payload"));
    when(finalizer.finalizeOne(3L, now)).thenReturn(true);
    Logger logger = (Logger) LoggerFactory.getLogger(ActivityFinalizationJob.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start(); logger.addAppender(appender);
    MDC.put("requestId", "finalization-test");
    try {
      assertThat(job.finalizeBatch()).isEqualTo(2);
      verify(finalizer).finalizeOne(3L, now);
      assertThat(appender.list).singleElement().satisfies(event -> {
        assertThat(event.getFormattedMessage()).contains("activityId=2", "requestId=finalization-test")
            .doesNotContain("private-audit-payload");
        assertThat(event.getThrowableProxy()).isNull();
      });
      assertThat(MDC.get("requestId")).isEqualTo("finalization-test");
    } finally { MDC.remove("requestId"); logger.detachAppender(appender); appender.stop(); }
  }

  @Test void losingClaimsAreNotCountedAndShortPageStillAdvances() {
    when(activities.selectDueIds(now, null, null, 2)).thenReturn(List.of(new DueActivityId(1L, end)));
    when(activities.selectDueIds(now, end, 1L, 2)).thenReturn(List.of());
    assertThat(job.finalizeBatch()).isZero();
    verify(finalizer).finalizeOne(1L, now);
    verify(activities).selectDueIds(now, end, 1L, 2);
  }

  @Test void scheduledRunCreatesAndClearsRequestId() {
    MDC.remove("requestId");
    List<String> observed = new ArrayList<>();
    when(activities.selectDueIds(now, null, null, 2)).thenAnswer(call -> {
      observed.add(MDC.get("requestId")); return List.of(new DueActivityId(1L, end));
    });
    when(finalizer.finalizeOne(1L, now)).thenAnswer(call -> {
      observed.add(MDC.get("requestId")); return true;
    });
    assertThat(job.finalizeBatch()).isEqualTo(1);
    assertThat(observed).hasSize(2).allSatisfy(id -> assertThat(id).isNotBlank());
    assertThat(observed.get(0)).isEqualTo(observed.get(1));
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test void scanFailureRestoresExistingRequestId() {
    MDC.put("requestId", "outer-request");
    when(activities.selectDueIds(now, null, null, 2)).thenThrow(new IllegalStateException("scan failure"));
    try {
      assertThatThrownBy(job::finalizeBatch).isInstanceOf(IllegalStateException.class);
      assertThat(MDC.get("requestId")).isEqualTo("outer-request");
    } finally { MDC.remove("requestId"); }
  }
}
