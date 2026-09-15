package com.volunteerflow.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityPosition;
import com.volunteerflow.audit.AuditLog;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.RefreshSession;
import com.volunteerflow.infrastructure.security.JwtProperties;
import com.volunteerflow.organization.Organization;
import com.volunteerflow.organization.OrganizationInvite;
import com.volunteerflow.organization.OrganizationMember;
import com.volunteerflow.rbac.RbacPermission;
import com.volunteerflow.rbac.RbacRole;
import com.volunteerflow.rbac.RbacRolePermission;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LombokEntityContractTest {

  @ParameterizedTest(name = "{0} does not expose all fields through toString")
  @MethodSource("mutableDataClasses")
  void mutableDataClassesDoNotDeclareToString(Class<?> type) {
    assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod("toString"));
  }

  @ParameterizedTest(name = "{0} keeps identity semantics")
  @MethodSource("mutableDataClasses")
  void mutableDataClassesDoNotDeclareEqualsOrHashCode(Class<?> type) {
    assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod("equals", Object.class));
    assertThrows(NoSuchMethodException.class, () -> type.getDeclaredMethod("hashCode"));
  }

  @Test
  void lombokStillGeneratesBeanAccessors() throws NoSuchMethodException {
    AppUser.class.getDeclaredMethod("getUsername");
    AppUser.class.getDeclaredMethod("setUsername", String.class);
  }

  private static Stream<Class<?>> mutableDataClasses() {
    return Stream.of(
        Activity.class,
        ActivityPosition.class,
        AuditLog.class,
        AppUser.class,
        RefreshSession.class,
        JwtProperties.class,
        Organization.class,
        OrganizationInvite.class,
        OrganizationMember.class,
        RbacPermission.class,
        RbacRole.class,
        RbacRolePermission.class);
  }
}
