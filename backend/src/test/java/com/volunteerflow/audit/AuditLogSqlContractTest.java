package com.volunteerflow.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.volunteerflow.audit.AuditLogViews.Filter;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies generated SQL/bindings without claiming live MySQL execution. */
class AuditLogSqlContractTest {
  @Test
  void scopesEveryQueryAndOrdersSameTimestampRowsById() {
    var bound = sql(new Filter(null, null, null, null, null, null));
    assertThat(bound.getSql().replaceAll("\\s+", " ").trim())
        .contains("WHERE organization_id=?")
        .endsWith("ORDER BY create_time DESC, id DESC");
    assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
        .containsExactly("organizationId");
  }

  @Test
  void bindsTenantAndAllFiltersWithoutInterpolatingUserInput() {
    var bound = sql(new Filter(21L, "' OR 1=1 --", "activity", 50L,
        LocalDateTime.of(2026, 10, 1, 0, 0), LocalDateTime.of(2026, 11, 1, 0, 0)));
    assertThat(bound.getSql().replaceAll("\\s+", " ").trim())
        .contains("organization_id=?", "actor_user_id=?", "action=?", "resource_type=?",
            "resource_id=?", "create_time >= ?", "create_time < ?")
        .doesNotContain("OR 1=1");
    assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
        .containsExactly("organizationId", "filter.actorUserId", "filter.action", "filter.resourceType",
            "filter.resourceId", "filter.from", "filter.to");
  }

  private org.apache.ibatis.mapping.BoundSql sql(Filter filter) {
    var configuration = new MybatisConfiguration();
    configuration.addMapper(AuditLogMapper.class);
    return configuration.getMappedStatement(AuditLogMapper.class.getName() + ".selectForOrganization")
        .getBoundSql(Map.of("organizationId", 100L, "filter", filter));
  }
}
