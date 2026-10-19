package com.volunteerflow.infrastructure.event;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Test-only failpoint assertion: never accept an early failure or print a raw database exception. */
public final class Stage5SqlFailureAssertions {
  private Stage5SqlFailureAssertions() {}

  public static SQLException assertInjectedFailure(Throwable failure) {
    var pending = new ArrayDeque<Throwable>();
    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
    if (failure != null) pending.add(failure);
    while (!pending.isEmpty()) {
      Throwable current = pending.removeFirst();
      if (!seen.add(current)) continue;
      if (current instanceof SQLException sql) {
        if ("45000".equals(sql.getSQLState()) && sql.getMessage() != null
            && sql.getMessage().contains("TASK8_INJECTED_FAILURE")) return sql;
        if (sql.getNextException() != null) pending.add(sql.getNextException());
      }
      if (current.getCause() != null) pending.add(current.getCause());
    }
    throw new AssertionError("Expected TASK8_INJECTED_FAILURE with SQLSTATE 45000; database failpoint was not reached");
  }
}
