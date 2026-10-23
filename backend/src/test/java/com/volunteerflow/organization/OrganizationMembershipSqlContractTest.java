package com.volunteerflow.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifies emitted authorization SQL and bindings, not live MySQL locking behavior. */
class OrganizationMembershipSqlContractTest {
  @ParameterizedTest
  @ValueSource(strings = {"selectActiveMember", "selectActiveMemberForUpdate"})
  void authorizationRequiresBothActiveMembershipAndActiveOrganization(String method) {
    BoundSql bound = bound(method);
    String sql = sql(bound);
    assertThat(sql).contains("organization_id=?", "user_id=?", "status='ACTIVE'",
        "EXISTS (SELECT 1 FROM organization o WHERE o.id=m.organization_id AND o.status='ACTIVE')");
    assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
        .containsExactly("orgId", "userId");
    if (method.endsWith("ForUpdate")) {
      assertThat(sql).endsWith("LIMIT 1 FOR UPDATE");
      // Only the member query locks; the organization subquery must remain non-locking.
      assertThat(sql.substring(sql.indexOf("EXISTS"), sql.indexOf(")", sql.indexOf("EXISTS"))))
          .doesNotContain("FOR UPDATE", "FOR SHARE");
    } else {
      assertThat(sql).doesNotContain("FOR UPDATE");
    }
  }

  @Test
  void expiryCleanupCanStillLockMembershipRegardlessOfOrganizationOrMemberStatus() {
    String sql = sql(bound("selectMemberForUpdateAnyStatus"));
    assertThat(sql).contains("organization_id=?", "user_id=?")
        .endsWith("LIMIT 1 FOR UPDATE")
        .doesNotContain("EXISTS", "status=", "JOIN organization");
  }

  private BoundSql bound(String method) {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.addMapper(OrganizationMemberMapper.class);
    return configuration.getMappedStatement(OrganizationMemberMapper.class.getName() + "." + method)
        .getBoundSql(Map.of("orgId", 100L, "userId", 2L));
  }

  private String sql(BoundSql bound) {
    return bound.getSql().replaceAll("\\s+", " ").trim();
  }
}
