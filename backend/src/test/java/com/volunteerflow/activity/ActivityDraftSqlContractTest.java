package com.volunteerflow.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ActivityDraftSqlContractTest {
  @Test
  void draftActivityReplacementBindsNullCancellationDeadlineAndGuardsDraftVersion() {
    var config = new MybatisConfiguration();
    config.addMapper(ActivityMapper.class);
    Activity draft = new Activity();
    draft.setId(10L);
    draft.setOrganizationId(100L);
    draft.setVersion(2);
    var bound =
        config
            .getMappedStatement(ActivityMapper.class.getName() + ".updateDraftFields")
            .getBoundSql(draft);
    assertThat(bound.getSql().replaceAll("\\s+", " "))
        .contains(
            "free_cancel_deadline_at=?",
            "organization_id=?",
            "status='DRAFT'",
            "version=version+1",
            "version=?");
    var deadline =
        bound.getParameterMappings().stream()
            .filter(mapping -> "freeCancelDeadlineAt".equals(mapping.getProperty()))
            .findFirst()
            .orElseThrow();
    assertThat(deadline.getJdbcType()).isEqualTo(org.apache.ibatis.type.JdbcType.TIMESTAMP);
    assertThat(config.newMetaObject(draft).getValue(deadline.getProperty())).isNull();
  }

  @Test
  void draftPageIsOrganizationScopedAndStableMostRecentlyUpdatedFirst() {
    var config = new MybatisConfiguration();
    config.addMapper(ActivityMapper.class);
    String sql =
        config
            .getMappedStatement(ActivityMapper.class.getName() + ".selectDraftPage")
            .getBoundSql(Map.of("orgId", 100L))
            .getSql()
            .replaceAll("\\s+", " ");
    assertThat(sql)
        .contains("organization_id=?", "status='DRAFT'", "ORDER BY update_time DESC,id DESC");
  }

  @Test
  void draftPositionUpdateIncludesNullsAndVersionGuardWhileDeleteKeepsCoreRow() {
    var config = new MybatisConfiguration();
    config.addMapper(ActivityPositionMapper.class);
    ActivityPosition position = new ActivityPosition();
    position.setId(20L);
    position.setOrganizationId(100L);
    position.setActivityId(10L);
    position.setVersion(2);
    var bound =
        config
            .getMappedStatement(ActivityPositionMapper.class.getName() + ".updateDraftFields")
            .getBoundSql(position);
    assertThat(bound.getSql().replaceAll("\\s+", " "))
        .contains(
            "service_start_at=?",
            "service_end_at=?",
            "meeting_location=?",
            "registration_mode=?",
            "version=version+1",
            "version=?",
            "status='ACTIVE'");
    String deletion =
        config
            .getMappedStatement(ActivityPositionMapper.class.getName() + ".markDraftDeleted")
            .getBoundSql(Map.of("positionId", 20L, "organizationId", 100L, "activityId", 10L))
            .getSql()
            .replaceAll("\\s+", " ");
    assertThat(deletion)
        .startsWith("UPDATE activity_position SET status='DELETED'")
        .contains("organization_id=?", "activity_id=?", "status='ACTIVE'", "version=version+1");
  }
}
