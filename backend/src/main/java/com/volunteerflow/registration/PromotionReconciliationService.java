package com.volunteerflow.registration;

import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class PromotionReconciliationService {
  private final PromotionReconcileTaskMapper tasks;
  private final ActivityRegistrationPolicyService policies;
  private final PromotionService promotions;
  private final PromotionReconciliationProperties properties;

  public PromotionReconciliationService(PromotionReconcileTaskMapper tasks,
      ActivityRegistrationPolicyService policies, PromotionService promotions,
      PromotionReconciliationProperties properties) {
    this.tasks = tasks;
    this.policies = policies;
    this.promotions = promotions;
    this.properties = properties;
  }

  /** No business locks are taken while claiming task rows; commit this short transaction first. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public List<PromotionReconcileTask> claimBatch(String workerId) {
    requireOwner(workerId);
    LocalDateTime now = tasks.currentDatabaseTime();
    LocalDateTime until = now.plus(properties.getLease());
    List<PromotionReconcileTask> claimed = tasks.selectClaimableForUpdate(now, properties.getBatchSize());
    for (PromotionReconcileTask task : claimed) {
      if (tasks.claim(task.getPositionId(), task.getRequestVersion(), workerId, until, now) != 1) {
        throw new IllegalStateException("Promotion reconciliation claim changed under lock");
      }
      task.setStatus("PROCESSING");
      task.setLockedBy(workerId);
      task.setLockedUntil(until);
    }
    return claimed;
  }

  /**
   * One position per transaction. Position precedes task/cycle/offer locks, as in request hooks;
   * automatic selection never locks membership after position. Owner is explicit to fence ABA.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public ReconciliationResult reconcile(Long positionId, long claimedVersion, String workerId) {
    requireOwner(workerId);
    ActivityPosition position = policies.lockPositionForRegistrationChange(positionId);
    PromotionReconcileTask task = tasks.selectByPositionForUpdate(positionId);
    if (!owns(task, claimedVersion, workerId, tasks.currentDatabaseTime())) {
      return ReconciliationResult.STALE;
    }
    PromotionOffer offer = null;
    if (position != null && Objects.equals(positionId, position.getId())
        && Objects.equals(task.getOrganizationId(), position.getOrganizationId())) {
      offer = promotions.offerNextFirstCome(positionId, null, "Waitlist reconciliation");
    }
    LocalDateTime now = tasks.currentDatabaseTime();
    if (offer != null) {
      // Bound work to one offer per transaction; another version drains any remaining free seats.
      if (tasks.upsertRequest(task.getOrganizationId(), positionId, now) <= 0) {
        throw new IllegalStateException("Promotion reconciliation continuation was not persisted");
      }
    }
    if (tasks.markProcessed(positionId, claimedVersion, workerId, now) != 1) {
      // Also roll back the offer/audit/Outbox when processing outlives its lease.
      throw new IllegalStateException("Promotion reconciliation lease lost");
    }
    return offer == null ? ReconciliationResult.UNCHANGED : ReconciliationResult.OFFERED;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
  public boolean retry(PromotionReconcileTask claimed, String workerId) {
    requireOwner(workerId);
    PromotionReconcileTask task = tasks.selectByPositionForUpdate(claimed.getPositionId());
    LocalDateTime now = tasks.currentDatabaseTime();
    if (!owns(task, claimed.getRequestVersion(), workerId, now)) return false;
    int attempts = task.getAttemptCount() == Integer.MAX_VALUE
        ? Integer.MAX_VALUE : task.getAttemptCount() + 1;
    Duration delay = properties.getRetryInitial();
    for (int exponent = 1; exponent < attempts && delay.compareTo(properties.getRetryMax()) < 0; exponent++) {
      delay = delay.compareTo(properties.getRetryMax().dividedBy(2)) > 0
          ? properties.getRetryMax() : delay.multipliedBy(2);
    }
    return tasks.markRetry(task.getPositionId(), claimed.getRequestVersion(), workerId, attempts,
        now.plus(delay), "RECONCILIATION_FAILED", now) == 1;
  }

  private boolean owns(PromotionReconcileTask task, long version, String owner, LocalDateTime now) {
    return task != null && "PROCESSING".equals(task.getStatus())
        && owner.equals(task.getLockedBy()) && task.getLockedUntil() != null
        && task.getLockedUntil().isAfter(now) && task.getProcessedVersion() < version
        && task.getRequestVersion() >= version;
  }

  private void requireOwner(String owner) {
    if (owner == null || owner.isBlank() || owner.length() > 128) {
      throw new IllegalArgumentException("Invalid promotion reconciliation lease owner");
    }
  }

  public enum ReconciliationResult { OFFERED, UNCHANGED, STALE }
}
