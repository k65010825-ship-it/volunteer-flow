package com.volunteerflow.registration;

import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scans without row locks and delegates every candidate to a separate transactional bean. */
@Component
@Profile("!test")
public class PromotionExpiryJob {
  private static final Logger log = LoggerFactory.getLogger(PromotionExpiryJob.class);
  private final PromotionOfferMapper offers;
  private final PromotionExpiryService expiry;
  private final PromotionExpiryProperties properties;

  public PromotionExpiryJob(
      PromotionOfferMapper offers,
      PromotionExpiryService expiry,
      PromotionExpiryProperties properties) {
    this.offers = offers;
    this.expiry = expiry;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${volunteerflow.registration.promotion-expiry.scan-delay:30s}")
  public int expireBatch() {
    LocalDateTime now = offers.currentDatabaseTime();
    int processed = 0;
    for (Long offerId : offers.selectExpiredIds(now, properties.getBatchSize())) {
      try {
        if (expiry.expireOne(offerId)) {
          processed++;
        }
      } catch (RuntimeException exception) {
        // A failed item is retried by a future scan; committed predecessors stay committed.
        log.warn("Failed to expire promotion offer {}; continuing batch", offerId, exception);
      }
    }
    return processed;
  }
}
