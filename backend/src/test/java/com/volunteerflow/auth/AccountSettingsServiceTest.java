package com.volunteerflow.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.volunteerflow.infrastructure.web.BusinessException;
import java.time.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AccountSettingsServiceTest {
  final AppUserMapper users = mock(AppUserMapper.class);
  final RefreshSessionMapper sessions = mock(RefreshSessionMapper.class);
  final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
  final AppUser user = new AppUser();
  final AuthService service =
      new AuthService(
          users,
          sessions,
          encoder,
          u -> new IssuedAccessToken("access", Instant.now().plusSeconds(60)),
          () -> "refresh",
          Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC));

  @BeforeEach
  void fixture() {
    user.setId(1L);
    user.setUsername("student");
    user.setStudentNumber("20260001");
    user.setRealName("Old");
    user.setContact("old-contact");
    user.setPlatformRole("USER");
    user.setStatus("ACTIVE");
    user.setVersion(0);
    user.setPasswordHash(encoder.encode("old-password-123"));
    when(users.selectById(1L)).thenReturn(user);
    when(users.selectByIdForUpdate(1L)).thenReturn(user);
    when(users.updatePassword(eq(1L), anyString(), eq(0))).thenReturn(1);
    when(users.updateProfile(eq(1L), anyString(), anyString())).thenReturn(1);
  }

  @Test
  void profileTrimsAndChangesOnlyEditableFieldsWithoutInvalidatingCredentials() {
    CurrentUser result =
        service.updateProfile(1L, new UpdateProfileRequest(" New Name ", " new-contact "));
    assertThat(result.realName()).isEqualTo("New Name");
    assertThat(result.contact()).isEqualTo("new-contact");
    assertThat(result.username()).isEqualTo("student");
    assertThat(result.studentNumber()).isEqualTo("20260001");
    assertThat(result.platformRole()).isEqualTo("USER");
    assertThat(user.getVersion()).isZero();
    verify(users).updateProfile(1L, "New Name", "new-contact");
    verifyNoInteractions(sessions);
  }

  @Test
  void passwordChangeHashesNewSecretIncrementsGenerationAndRevokesAllDevices() {
    service.changePassword(1L, new ChangePasswordRequest("old-password-123", "new-password-123"));
    var hash = org.mockito.ArgumentCaptor.forClass(String.class);
    var ordered = inOrder(users, sessions);
    ordered.verify(users).selectByIdForUpdate(1L);
    ordered.verify(users).updatePassword(eq(1L), hash.capture(), eq(0));
    ordered.verify(sessions).revokeAllForUser(1L, LocalDateTime.parse("2026-10-24T00:00:00"));
    assertThat(hash.getValue()).isNotEqualTo("new-password-123");
    assertThat(encoder.matches("new-password-123", hash.getValue())).isTrue();
  }

  @Test
  void wrongOldPasswordDoesNotMutateAnyCredentialOrSession() {
    assertThatThrownBy(
            () ->
                service.changePassword(1L, new ChangePasswordRequest("wrong", "new-password-123")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo("INVALID_CURRENT_PASSWORD"));
    verify(users, never()).updatePassword(anyLong(), anyString(), anyInt());
    verifyNoInteractions(sessions);
  }

  @Test
  void samePasswordIsRejected() {
    assertThatThrownBy(
            () ->
                service.changePassword(
                    1L, new ChangePasswordRequest("old-password-123", "old-password-123")))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("PASSWORD_UNCHANGED"));
    verifyNoInteractions(sessions);
  }

  @Test
  void bcryptUtf8ByteLimitIsEnforcedBeforeEncoding() {
    assertThatThrownBy(
            () ->
                service.changePassword(
                    1L, new ChangePasswordRequest("old-password-123", "密".repeat(25))))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("INVALID_NEW_PASSWORD"));
    verify(users, never()).updatePassword(anyLong(), anyString(), anyInt());
    verifyNoInteractions(sessions);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"short", "            "})
  void rejectsShortAndBlankNewPasswords(String password) {
    assertThatThrownBy(
            () ->
                service.changePassword(1L, new ChangePasswordRequest("old-password-123", password)))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(sessions);
  }

  @Test
  void exactly72Utf8BytesCanBeUsedAsNewPassword() {
    service.changePassword(1L, new ChangePasswordRequest("old-password-123", "密".repeat(24)));
    verify(users).updatePassword(eq(1L), anyString(), eq(0));
  }

  @Test
  void revocationFailureRollsBackCredentialTransaction() throws Exception {
    javax.sql.DataSource dataSource = mock(javax.sql.DataSource.class);
    java.sql.Connection connection = mock(java.sql.Connection.class);
    when(dataSource.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    var transactionManager =
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    var factory = new org.springframework.aop.framework.ProxyFactory(service);
    factory.addAdvice(
        new org.springframework.transaction.interceptor.TransactionInterceptor(
            transactionManager,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
    AuthService transactionalService = (AuthService) factory.getProxy();
    doThrow(new IllegalStateException("session write failed"))
        .when(sessions)
        .revokeAllForUser(anyLong(), any());
    assertThatThrownBy(
            () ->
                transactionalService.changePassword(
                    1L, new ChangePasswordRequest("old-password-123", "new-password-123")))
        .isInstanceOf(IllegalStateException.class);
    verify(connection).rollback();
    verify(connection, never()).commit();
  }

  @Test
  void failedCredentialUpdateDoesNotRevokeSessions() {
    when(users.updatePassword(eq(1L), anyString(), eq(0))).thenReturn(0);
    assertThatThrownBy(
            () ->
                service.changePassword(
                    1L, new ChangePasswordRequest("old-password-123", "new-password-123")))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(sessions);
  }

  @Test
  void loginLocksUserBeforeCreatingRefreshSession() {
    when(users.selectOne(any())).thenReturn(user);
    when(users.selectByUsernameForUpdate("student")).thenReturn(user);
    service.login(new LoginRequest("student", "old-password-123"), "device");
    var ordered = inOrder(users, sessions);
    ordered.verify(users).selectByUsernameForUpdate("student");
    ordered.verify(sessions).insert(any(RefreshSession.class));
  }

  @Test
  void refreshLocksUserBeforeSessionAndRechecksRevocationAfterWaiting() {
    RefreshSession snapshot = new RefreshSession();
    snapshot.setUserId(1L);
    snapshot.setTokenHash(AuthService.hash("refresh"));
    snapshot.setExpiresAt(LocalDateTime.parse("2026-10-25T00:00:00"));
    RefreshSession revoked = new RefreshSession();
    revoked.setUserId(1L);
    revoked.setTokenHash(snapshot.getTokenHash());
    revoked.setExpiresAt(snapshot.getExpiresAt());
    revoked.setRevokedAt(LocalDateTime.parse("2026-10-24T00:00:00"));
    when(sessions.selectOne(any())).thenReturn(snapshot);
    when(sessions.selectByTokenHashForUpdate(anyString())).thenReturn(revoked);
    assertThatThrownBy(() -> service.refresh("refresh", "device"))
        .isInstanceOf(BusinessException.class);
    var ordered = inOrder(users, sessions);
    ordered.verify(sessions).selectOne(any());
    ordered.verify(users).selectByIdForUpdate(1L);
    ordered.verify(sessions).selectByTokenHashForUpdate(AuthService.hash("refresh"));
    verify(sessions, never()).updateById(any(RefreshSession.class));
  }
}
