package com.volunteerflow.registration;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class PromotionReconciliationJob {
  private static final Logger log = LoggerFactory.getLogger(PromotionReconciliationJob.class);
  private final PromotionReconciliationService reconciliation;

  public PromotionReconciliationJob(PromotionReconciliationService reconciliation) {
    this.reconciliation = reconciliation;
  }

  @Scheduled(fixedDelayString = "${volunteerflow.registration.promotion-reconciliation.scan-delay:30s}")
  public int reconcileBatch() {
    String owner = UUID.randomUUID().toString();
    List<PromotionReconcileTask> claimed;
    try {
      claimed = reconciliation.claimBatch(owner);
    } catch (RuntimeException failure) {
      log.warn("Promotion reconciliation failed category=CLAIM_FAILED");
      return 0;
    }
    int processed = 0;
    for (PromotionReconcileTask task : claimed) {
      try {
        if (reconciliation.reconcile(task.getPositionId(), task.getRequestVersion(), owner)
            != PromotionReconciliationService.ReconciliationResult.STALE) processed++;
      } catch (RuntimeException failure) {
        log.warn("Promotion reconciliation failed; positionId={} category=RECONCILIATION_FAILED", task.getPositionId());
        try {
          reconciliation.retry(task, owner);
        } catch (RuntimeException retryFailure) {
          // Leave PROCESSING for indexed expired-lease recovery; arbitrary messages are never logged.
          log.warn("Promotion reconciliation failed; positionId={} category=RETRY_STATE_FAILED", task.getPositionId());
        }
      }
    }
    return processed;
  }
}
