package com.volunteerflow.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountSettingsSqlTest {
  @Test
  void profileUpdateBindsOnlyAllowedFieldsAndAuthenticatedId() {
    var bound =
        statement(
            AppUserMapper.class,
            "updateProfile",
            Map.of("id", 1L, "realName", "New", "contact", "contact"));
    assertThat(bound.getSql()).isEqualTo("UPDATE app_user SET real_name=?, contact=? WHERE id=?");
    assertThat(bound.getParameterMappings())
        .extracting("property")
        .containsExactly("realName", "contact", "id");
  }

  @Test
  void credentialUpdateAdvancesVersionAndAllDeviceRevocationIsUserScoped() {
    var password =
        statement(
            AppUserMapper.class,
            "updatePassword",
            Map.of("id", 1L, "passwordHash", "hash", "version", 0));
    assertThat(password.getSql()).contains("version=version+1", "WHERE id=? AND version=?");
    var revoke =
        statement(
            RefreshSessionMapper.class,
            "revokeAllForUser",
            Map.of("userId", 1L, "now", java.time.LocalDateTime.now()));
    assertThat(revoke.getSql())
        .isEqualTo(
            "UPDATE refresh_session SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL");
    assertThat(revoke.getParameterMappings())
        .extracting("property")
        .containsExactly("now", "userId");
    assertThat(statement(AppUserMapper.class, "selectByIdForUpdate", Map.of("id", 1L)).getSql())
        .endsWith("WHERE id=? FOR UPDATE");
    assertThat(
            statement(AppUserMapper.class, "selectByUsernameForUpdate", Map.of("username", "user"))
                .getSql())
        .endsWith("WHERE username=? LIMIT 1 FOR UPDATE");
  }

  private org.apache.ibatis.mapping.BoundSql statement(
      Class<?> mapper, String method, Map<String, ?> params) {
    var config = new MybatisConfiguration();
    config.addMapper(mapper);
    return config.getMappedStatement(mapper.getName() + "." + method).getBoundSql(params);
  }
}
