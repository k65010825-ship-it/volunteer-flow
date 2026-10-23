package com.volunteerflow.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Emitted SQL contract only; no live database is contacted. */
class RolePermissionCodesSqlContractTest {
  @Test
  void grantProjectionScopesRoleAndOrganizationAndExcludesInactiveCatalogEntries() {
    MybatisConfiguration config = new MybatisConfiguration();
    config.addMapper(RbacRolePermissionMapper.class);
    var sql = config.getMappedStatement(RbacRolePermissionMapper.class.getName()
        + ".selectActivePermissionCodes").getBoundSql(Map.of("orgId", 100L, "roleId", 30L));
    assertThat(sql.getSql().replaceAll("\\s+", " ").trim()).contains(
        "SELECT DISTINCT p.code", "p.id=rp.permission_id", "rp.organization_id=?",
        "rp.role_id=?", "p.status='ACTIVE'", "ORDER BY p.code");
    assertThat(sql.getParameterMappings()).extracting(p -> p.getProperty())
        .containsExactly("orgId", "roleId");
  }
}
