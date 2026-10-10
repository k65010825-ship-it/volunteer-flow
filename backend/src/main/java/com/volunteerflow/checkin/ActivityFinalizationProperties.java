package com.volunteerflow.checkin;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Hard bounds on due-activity pages per scheduled run, independent of backlog size. */
@Getter
@Setter
@Validated
@ConfigurationProperties("volunteerflow.checkin.finalization")
public class ActivityFinalizationProperties {
  @NotNull
  private Duration scanDelay = Duration.ofSeconds(30);

  @Min(1) @Max(500)
  private int batchSize = 100;

  @Min(1) @Max(100)
  private int maxBatchesPerRun = 10;

  @AssertTrue(message = "scan-delay must be at least one millisecond")
  public boolean isScanDelayValid() {
    return scanDelay != null && scanDelay.compareTo(Duration.ofMillis(1)) >= 0;
  }
}
