package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActivityCompletionSqlTest {
  @Test
  void manualClaimBindsOrganizationAndPublishedStateWithoutChangingPlannedTime() {
    var configuration = new MybatisConfiguration();
    configuration.addMapper(ActivityMapper.class);
    var bound = configuration.getMappedStatement(ActivityMapper.class.getName() + ".claimFinishedManually")
        .getBoundSql(Map.of("id", 11L, "organizationId", 100L));
    assertThat(bound.getSql().replaceAll("\\s+", " ").trim())
        .contains("status='FINISHED'", "version=version+1", "id=?", "organization_id=?", "status='PUBLISHED'")
        .doesNotContain("activity_end_at", "now");
    assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
        .containsExactly("id", "organizationId");
  }
}
