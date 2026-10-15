package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DynamicCheckinPropertiesTest {
  @Test
  void rejectsTtlNotGreaterThanWindow() {
    assertThatThrownBy(() -> new DynamicCheckinProperties(Duration.ofSeconds(30), Duration.ofSeconds(30), 24))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
