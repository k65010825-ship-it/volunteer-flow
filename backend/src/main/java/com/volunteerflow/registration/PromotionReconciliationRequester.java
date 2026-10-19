package com.volunteerflow.registration;

import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The durable request must commit or roll back with the capacity-releasing business action. */
@Service
@Profile("!test")
public class PromotionReconciliationRequester {
  private final PromotionReconcileTaskMapper tasks;

  public PromotionReconciliationRequester(PromotionReconcileTaskMapper tasks) {
    this.tasks = tasks;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void request(Long organizationId, Long positionId, LocalDateTime nextRunAt) {
    if (organizationId == null || organizationId <= 0 || positionId == null || positionId <= 0
        || nextRunAt == null) {
      throw new IllegalArgumentException("Invalid promotion reconciliation request");
    }
    if (tasks.upsertRequest(organizationId, positionId, nextRunAt) <= 0) {
      throw new IllegalStateException("Promotion reconciliation request was not persisted");
    }
  }
}
