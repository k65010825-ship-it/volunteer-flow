package com.volunteerflow.checkin;

import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.activity.ActivityMapper.DueActivityId;
import com.volunteerflow.infrastructure.web.RequestIdFilter;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Non-transactional bounded scanner. Every ID crosses the separate service's REQUIRES_NEW proxy. */
@Component
@Profile("!test")
public class ActivityFinalizationJob {
  private static final Logger log = LoggerFactory.getLogger(ActivityFinalizationJob.class);
  private final ActivityMapper activities;
  private final ActivityFinalizationService finalizer;
  private final ActivityFinalizationProperties properties;
  private final Clock clock;

  public ActivityFinalizationJob(ActivityMapper activities, ActivityFinalizationService finalizer,
      ActivityFinalizationProperties properties, Clock clock) {
    this.activities = activities; this.finalizer = finalizer; this.properties = properties; this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${volunteerflow.checkin.finalization.scan-delay:30s}")
  public int finalizeBatch() {
    String previousRequestId = MDC.get(RequestIdFilter.MDC_KEY);
    String requestId = previousRequestId == null || previousRequestId.isBlank()
        ? UUID.randomUUID().toString() : previousRequestId;
    MDC.put(RequestIdFilter.MDC_KEY, requestId);
    try {
      LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
      LocalDateTime cursorEndAt = null;
      Long cursorId = null;
      int processed = 0;
      for (int batch = 0; batch < properties.getMaxBatchesPerRun(); batch++) {
        List<DueActivityId> page = activities.selectDueIds(now, cursorEndAt, cursorId, properties.getBatchSize());
        if (page.isEmpty()) break;
        for (DueActivityId due : page) {
          try {
            if (finalizer.finalizeOne(due.id(), now)) processed++;
          } catch (RuntimeException failure) {
            // Do not include arbitrary exception payloads. A later run starts fresh and retries failed claims.
            log.warn("Activity finalization failed activityId={} requestId={}; continuing scan", due.id(), requestId);
          }
        }
        // Move past the last candidate even if it failed. Never reset a full-page cursor within this run.
        DueActivityId last = page.get(page.size() - 1);
        cursorEndAt = last.activityEndAt(); cursorId = last.id();
      }
      return processed;
    } finally {
      if (previousRequestId == null) MDC.remove(RequestIdFilter.MDC_KEY);
      else MDC.put(RequestIdFilter.MDC_KEY, previousRequestId);
    }
  }
}
