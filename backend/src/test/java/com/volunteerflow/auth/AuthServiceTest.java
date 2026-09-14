package com.volunteerflow.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private final AppUserMapper userMapper = mock(AppUserMapper.class);
    private final RefreshSessionMapper sessionMapper = mock(RefreshSessionMapper.class);
    private final AccessTokenIssuer accessTokenIssuer = user -> "access-for-" + user.getUsername();
    private final RefreshTokenGenerator refreshTokenGenerator = mock(RefreshTokenGenerator.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-14T08:00:00Z"), ZoneOffset.UTC);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userMapper, sessionMapper, new BCryptPasswordEncoder(4),
                accessTokenIssuer, refreshTokenGenerator, clock);
    }

    @Test
    void registrationAlwaysCreatesOrdinaryUser() {
        when(userMapper.selectCount(any())).thenReturn(0L, 0L);
        when(refreshTokenGenerator.generate()).thenReturn("refresh-secret");

        AuthTokens tokens = authService.register(new RegisterRequest(
                "student1", "Strong-password-123", "张三", "20260001", "student@example.test"), "Chrome");

        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getPlatformRole()).isEqualTo("USER");
        assertThat(userCaptor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(tokens.accessToken()).isEqualTo("access-for-student1");
    }

    @Test
    void rejectsDuplicateUsername() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        var request = new RegisterRequest("student1", "Strong-password-123", "张三", "20260001", "contact");

        assertThatThrownBy(() -> authService.register(request, null))
                .isInstanceOfSatisfying(com.volunteerflow.infrastructure.web.BusinessException.class,
                        ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void unknownUserAndWrongPasswordUseSameFailure() {
        when(userMapper.selectOne(any())).thenReturn(null);
        var unknown = catchLoginFailure("missing", "password");

        AppUser existing = activeUser("student1", new BCryptPasswordEncoder(4).encode("correct"));
        when(userMapper.selectOne(any())).thenReturn(existing);
        var wrong = catchLoginFailure("student1", "wrong");

        assertThat(unknown.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrong.code()).isEqualTo(unknown.code());
        assertThat(wrong.getMessage()).isEqualTo(unknown.getMessage());
    }

    @Test
    void disabledUserCannotLogin() {
        AppUser user = activeUser("student1", new BCryptPasswordEncoder(4).encode("correct"));
        user.setStatus("DISABLED");
        when(userMapper.selectOne(any())).thenReturn(user);

        assertThat(catchLoginFailure("student1", "correct").code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void loginStoresOnlyRefreshTokenHash() {
        AppUser user = activeUser("student1", new BCryptPasswordEncoder(4).encode("correct"));
        when(userMapper.selectOne(any())).thenReturn(user);
        when(refreshTokenGenerator.generate()).thenReturn("refresh-secret");

        AuthTokens tokens = authService.login(new LoginRequest("student1", "correct"), "Firefox");

        ArgumentCaptor<RefreshSession> captor = ArgumentCaptor.forClass(RefreshSession.class);
        verify(sessionMapper).insert(captor.capture());
        assertThat(captor.getValue().getTokenHash()).hasSize(64).doesNotContain("refresh-secret");
        assertThat(captor.getValue().getDeviceName()).isEqualTo("Firefox");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-secret");
    }

    private com.volunteerflow.infrastructure.web.BusinessException catchLoginFailure(String username, String password) {
        return org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> authService.login(new LoginRequest(username, password), null),
                com.volunteerflow.infrastructure.web.BusinessException.class);
    }

    private static AppUser activeUser(String username, String passwordHash) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setStatus("ACTIVE");
        user.setPlatformRole("USER");
        return user;
    }
}
