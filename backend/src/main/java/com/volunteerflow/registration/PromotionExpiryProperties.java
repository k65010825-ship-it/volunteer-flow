package com.volunteerflow.registration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Controls the bounded expiry scan; database time remains authoritative for deadlines. */
@Getter
@Setter
@Validated
@ConfigurationProperties("volunteerflow.registration.promotion-expiry")
public class PromotionExpiryProperties {
  @NotNull
  private Duration scanDelay = Duration.ofSeconds(30);

  @Min(1)
  private int batchSize = 100;

  @AssertTrue(message = "scan-delay must be at least one millisecond")
  public boolean isScanDelayValid() {
    return scanDelay != null && scanDelay.compareTo(Duration.ofMillis(1)) >= 0;
  }
}
