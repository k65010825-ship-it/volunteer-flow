package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class Stage5SqlFailureAssertionsTest {
  @Test
  void acceptsOnlyInjectedSqlFailureInTheDatabaseCauseChain() {
    var signal = new SQLException("TASK8_INJECTED_FAILURE", "45000", 1644);
    assertSame(signal, Stage5SqlFailureAssertions.assertInjectedFailure(new IllegalStateException("mapper failed", signal)));
  }

  @Test
  void acceptsDriverNextExceptionWithoutMistakingTheOuterErrorForTheFailpoint() {
    var outer = new SQLException("driver wrapper", "HY000");
    var signal = new SQLException("TASK8_INJECTED_FAILURE", "45000", 1644);
    outer.setNextException(signal);
    assertSame(signal, Stage5SqlFailureAssertions.assertInjectedFailure(outer));
  }

  @Test
  void rejectsAuthorizationValidationAndMissingFailureBeforeTheInsert() {
    for (Throwable early : new Throwable[]{null, new IllegalArgumentException("invalid request"),
        new IllegalStateException("authorization failed"), new RuntimeException("TASK8_INJECTED_FAILURE")}) {
      assertThatThrownBy(() -> Stage5SqlFailureAssertions.assertInjectedFailure(early))
          .isInstanceOf(AssertionError.class).hasMessageContaining("SQLSTATE 45000").hasNoCause();
    }
  }

  @Test
  void rejectsMarkerWithWrongSqlStateAndUnrelatedErrorWithTheRightState() {
    for (SQLException wrong : new SQLException[]{new SQLException("TASK8_INJECTED_FAILURE", "42000"),
        new SQLException("unrelated signal", "45000"), new SQLException(null, "45000")}) {
      assertThatThrownBy(() -> Stage5SqlFailureAssertions.assertInjectedFailure(wrong))
          .isInstanceOf(AssertionError.class).hasMessageContaining("TASK8_INJECTED_FAILURE").hasNoCause();
    }
  }
}
