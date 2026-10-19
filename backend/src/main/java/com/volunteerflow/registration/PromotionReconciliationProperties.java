package com.volunteerflow.registration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("volunteerflow.registration.promotion-reconciliation")
public class PromotionReconciliationProperties {
  @NotNull private Duration scanDelay = Duration.ofSeconds(30);
  @Min(1) @Max(1000) private int batchSize = 100;
  @NotNull private Duration lease = Duration.ofSeconds(60);
  @NotNull private Duration retryInitial = Duration.ofSeconds(5);
  @NotNull private Duration retryMax = Duration.ofMinutes(5);

  @AssertTrue(message = "delays and lease must be positive and retry-max must not precede retry-initial")
  public boolean isTimingValid() {
    return positive(scanDelay) && positive(lease) && positive(retryInitial)
        && positive(retryMax) && retryMax.compareTo(retryInitial) >= 0;
  }

  private boolean positive(Duration value) {
    return value != null && value.compareTo(Duration.ofMillis(1)) >= 0;
  }
}
