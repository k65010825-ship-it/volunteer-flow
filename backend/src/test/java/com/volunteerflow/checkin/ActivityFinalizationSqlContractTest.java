package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.registration.RegistrationCycleMapper;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Executable MyBatis SQL/binding contract only; optimizer/EXPLAIN is intentionally Task 7. */
class ActivityFinalizationSqlContractTest {
  private MybatisConfiguration configuration;

  @BeforeEach void setup() {
    configuration = new MybatisConfiguration();
    configuration.addMapper(ActivityMapper.class);
    configuration.addMapper(RegistrationCycleMapper.class);
  }

  @Test void firstPageUsesForcedCompositeIndexStatusEndOrderAndBoundLimit() {
    BoundSql bound = due(null, null);
    assertThat(sql(bound)).isEqualTo("SELECT id,activity_end_at FROM activity FORCE INDEX (idx_activity_status_end_id) "
        + "WHERE status='PUBLISHED' AND activity_end_at<=? ORDER BY activity_end_at,id LIMIT ?");
    assertThat(bindings(bound)).containsExactly("now", "batchSize");
    assertThat(sql(bound)).doesNotContainIgnoringCase("OFFSET");
  }

  @Test void laterPageUsesLexicographicEndIdCursorAndSameOrderWithoutOffset() {
    BoundSql bound = due(LocalDateTime.of(2026, 10, 10, 11, 0), 42L);
    assertThat(sql(bound)).isEqualTo("SELECT id,activity_end_at FROM activity FORCE INDEX (idx_activity_status_end_id) "
        + "WHERE status='PUBLISHED' AND activity_end_at<=? "
        + "AND (activity_end_at > ? OR (activity_end_at = ? AND id > ?)) "
        + "ORDER BY activity_end_at,id LIMIT ?");
    assertThat(bindings(bound)).containsExactly("now", "cursorEndAt", "cursorEndAt", "cursorId", "batchSize");
    assertThat(sql(bound)).doesNotContainIgnoringCase("OFFSET");
  }

  @Test void claimHasIdPublishedAndDueGuards() {
    BoundSql bound = bound(ActivityMapper.class, "claimFinished", Map.of("id", 11L, "now", LocalDateTime.now()));
    assertThat(sql(bound)).isEqualTo("UPDATE activity SET status='FINISHED' WHERE id=? AND status='PUBLISHED' AND activity_end_at <= ?");
    assertThat(bindings(bound)).containsExactly("id", "now");
  }

  @Test void attendanceCountAndUpdateBothExcludeOldAndNonConfirmedCycles() {
    Map<String, Object> parameters = Map.of("organizationId", 7L, "activityId", 11L);
    String count = sql(bound(RegistrationCycleMapper.class, "countCurrentConfirmedForActivity", parameters));
    String update = sql(bound(RegistrationCycleMapper.class, "finalizeCurrentConfirmedAttendance", parameters));
    for (String statement : List.of(count, update)) {
      assertThat(statement).contains("registration_cycle c FORCE INDEX (idx_cycle_activity_status_id)",
          "newer.registration_id=c.registration_id AND newer.cycle_number > c.cycle_number",
          "c.organization_id=? AND c.activity_id=? AND c.status='CONFIRMED' AND newer.id IS NULL");
    }
    assertThat(update).contains("LEFT JOIN checkin_record r ON r.activity_id=c.activity_id AND r.user_id=c.user_id",
        "r.organization_id=c.organization_id AND r.registration_cycle_id=c.id AND r.status='VALID'",
        "c.attendance_status=CASE WHEN r.id IS NOT NULL THEN 'CHECKED_IN' ELSE 'NO_SHOW' END",
        "c.version=c.version+1");
  }

  private BoundSql due(LocalDateTime cursorEndAt, Long cursorId) {
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("now", LocalDateTime.of(2026, 10, 10, 12, 0));
    parameters.put("cursorEndAt", cursorEndAt); parameters.put("cursorId", cursorId); parameters.put("batchSize", 2);
    return bound(ActivityMapper.class, "selectDueIds", parameters);
  }
  private BoundSql bound(Class<?> mapper, String method, Map<String, Object> parameters) {
    return configuration.getMappedStatement(mapper.getName() + "." + method).getBoundSql(parameters);
  }
  private String sql(BoundSql bound) { return bound.getSql().replaceAll("\\s+", " ").trim(); }
  private List<String> bindings(BoundSql bound) { return bound.getParameterMappings().stream().map(p -> p.getProperty()).toList(); }
}
