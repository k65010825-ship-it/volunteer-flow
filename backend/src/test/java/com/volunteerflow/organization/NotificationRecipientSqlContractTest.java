package com.volunteerflow.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationRecipientSqlContractTest {
  @Test
  void recipientsAreActiveAndOrganizationScopedWithOwnerOrActivePromotionPermission() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.addMapper(OrganizationMemberMapper.class);
    String id = OrganizationMemberMapper.class.getName() + ".selectNotificationRecipientIds";
    assertThat(configuration.hasStatement(id)).as("permission-scoped notification recipient query").isTrue();
    var bound = configuration.getMappedStatement(id)
        .getBoundSql(Map.of("orgId", 10L, "permissionCode", "registration:promote"));
    String sql = bound.getSql().replaceAll("\\s+", " ").trim();
    assertThat(sql).contains("SELECT DISTINCT m.user_id", "m.organization_id=?", "m.status='ACTIVE'",
        "r.organization_id=m.organization_id", "r.id=m.role_id", "r.status='ACTIVE'",
        "u.id=m.user_id", "u.status='ACTIVE'", "o.id=m.organization_id", "o.status='ACTIVE'",
        "r.protected_role=1 AND r.built_in_type='OWNER'", "rp.organization_id=m.organization_id",
        "rp.role_id=r.id", "p.id=rp.permission_id", "p.code=?", "p.status='ACTIVE'")
        .doesNotContain("FOR UPDATE", "FOR SHARE", "platform_role");
    assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
        .containsExactly("orgId", "permissionCode");
  }
}
