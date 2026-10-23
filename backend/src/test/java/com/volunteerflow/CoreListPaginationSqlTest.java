package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.activity.ActivityPositionMapper;
import com.volunteerflow.organization.OrganizationMemberMapper;
import com.volunteerflow.registration.RegistrationMapper;
import com.volunteerflow.infrastructure.persistence.MybatisPlusConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

/** SQL predicates/order contracts only; MP's IPage interceptor supplies count and LIMIT. */
class CoreListPaginationSqlTest {
  @Test
  void activityPageHasTenantPublishedScopeAndDeterministicOrder() {
    var bound = bound(ActivityMapper.class, "selectPublishedPage", Map.of("orgId", 7L));
    assertThat(sql(bound)).contains("organization_id=?", "status='PUBLISHED'")
        .endsWith("ORDER BY activity_start_at,id");
    assertThat(bindings(bound)).containsExactly("orgId");
  }

  @Test
  void ownRegistrationPageHasOwnerScopeAndDeterministicOrder() {
    var bound = bound(RegistrationMapper.class, "selectOwnedByUserPage", Map.of("userId", 21L));
    assertThat(sql(bound)).contains("user_id=?").endsWith("ORDER BY update_time DESC, id DESC");
    assertThat(bindings(bound)).containsExactly("userId");
  }

  @Test
  void memberPageHasTenantActiveScopeAndJoinedTimeTieBreaker() {
    var bound = bound(OrganizationMemberMapper.class, "selectActivePage", Map.of("orgId", 7L));
    assertThat(sql(bound)).contains("organization_id=?", "status='ACTIVE'")
        .endsWith("ORDER BY joined_at,id");
    assertThat(bindings(bound)).containsExactly("orgId");
  }

  @Test
  void batchPositionsAreTenantScopedAndActiveOnlyWithBoundPageIds() {
    var bound = bound(ActivityPositionMapper.class, "selectActiveByActivities",
        Map.of("orgId", 7L, "activityIds", List.of(11L, 12L)));
    assertThat(sql(bound)).contains("organization_id=?", "status='ACTIVE'", "activity_id IN")
        .endsWith("ORDER BY activity_id,id");
    assertThat(bindings(bound)).hasSize(3).startsWith("orgId");
  }

  @Test
  void installedPaginationPluginGeneratesCountAndBoundLimitForAllThreeLists() throws Exception {
    assertDatabasePaging(ActivityMapper.class, "selectPublishedPage", "orgId", "organization_id");
    assertDatabasePaging(OrganizationMemberMapper.class, "selectActivePage", "orgId", "organization_id");
    assertDatabasePaging(RegistrationMapper.class, "selectOwnedByUserPage", "userId", "user_id");
  }

  private void assertDatabasePaging(Class<?> mapper, String method, String parameter, String column)
      throws Exception {
    var configuration = new MybatisConfiguration();
    configuration.addMapper(mapper);
    var statement = configuration.getMappedStatement(mapper.getName() + "." + method);
    var page = new Page<>(3, 20);
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("page", page);
    parameters.put(parameter, 7L);
    var bound = statement.getBoundSql(parameters);
    Executor executor = mock(Executor.class);
    when(executor.createCacheKey(any(), any(), any(), any())).thenReturn(new CacheKey());
    when(executor.query(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
      BoundSql count = call.getArgument(5);
      assertThat(sql(count).toUpperCase()).contains("COUNT(");
      assertThat(sql(count)).contains(column + " = ?");
      return List.of(205L);
    });
    var plugin = (PaginationInnerInterceptor) new MybatisPlusConfig().mybatisPlusInterceptor()
        .getInterceptors().get(0);
    assertThat(plugin.willDoQuery(executor, statement, parameters, RowBounds.DEFAULT, null, bound)).isTrue();
    assertThat(page.getTotal()).isEqualTo(205);
    plugin.beforeQuery(executor, statement, parameters, RowBounds.DEFAULT, null, bound);
    assertThat(sql(bound)).matches("(?s).*LIMIT \\?,\\s*\\?$ ".trim());
    var mappings = bound.getParameterMappings();
    assertThat(bound.getAdditionalParameter(mappings.get(mappings.size() - 2).getProperty())).isEqualTo(40L);
    assertThat(bound.getAdditionalParameter(mappings.get(mappings.size() - 1).getProperty())).isEqualTo(20L);
  }

  private BoundSql bound(Class<?> mapper, String method, Map<String, Object> parameters) {
    var configuration = new MybatisConfiguration();
    configuration.addMapper(mapper);
    return configuration.getMappedStatement(mapper.getName() + "." + method).getBoundSql(parameters);
  }

  private String sql(BoundSql bound) {
    return bound.getSql().replaceAll("\\s+", " ").trim();
  }

  private List<String> bindings(BoundSql bound) {
    return bound.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList();
  }
}
