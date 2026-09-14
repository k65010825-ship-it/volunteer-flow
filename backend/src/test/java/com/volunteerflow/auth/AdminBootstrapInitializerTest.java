package com.volunteerflow.auth;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminBootstrapInitializerTest {

    private final AppUserMapper appUserMapper = mock(AppUserMapper.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @Test
    void createsFirstPlatformAdminWithEncodedPassword() {
        when(appUserMapper.selectCount(any())).thenReturn(0L);
        var properties = new AdminBootstrapProperties(
                "root-admin", "Strong-password-123", "平台管理员", "ADMIN-001", "admin@example.test");

        new AdminBootstrapInitializer(appUserMapper, passwordEncoder, properties).initialize();

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserMapper).insert(captor.capture());
        AppUser user = captor.getValue();
        assertThat(user.getUsername()).isEqualTo("root-admin");
        assertThat(user.getRealName()).isEqualTo("平台管理员");
        assertThat(user.getStudentNumber()).isEqualTo("ADMIN-001");
        assertThat(user.getContact()).isEqualTo("admin@example.test");
        assertThat(user.getStatus()).isEqualTo("ACTIVE");
        assertThat(user.getPlatformRole()).isEqualTo("PLATFORM_ADMIN");
        assertThat(passwordEncoder.matches("Strong-password-123", user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordHash()).isNotEqualTo("Strong-password-123");
    }

    @Test
    void doesNotCreateAnotherAdminWhenOneExists() {
        when(appUserMapper.selectCount(any())).thenReturn(1L);
        var properties = new AdminBootstrapProperties(
                "root-admin", "Strong-password-123", "平台管理员", "ADMIN-001", "admin@example.test");

        new AdminBootstrapInitializer(appUserMapper, passwordEncoder, properties).initialize();

        verify(appUserMapper, never()).insert(any(AppUser.class));
    }

    @Test
    void skipsBootstrapWhenEveryValueIsAbsent() {
        var properties = new AdminBootstrapProperties(null, null, null, null, null);

        new AdminBootstrapInitializer(appUserMapper, passwordEncoder, properties).initialize();

        verifyNoInteractions(appUserMapper);
    }

    @Test
    void rejectsPartialConfigurationBeforeAccessingDatabase() {
        var properties = new AdminBootstrapProperties("root-admin", null, null, null, null);
        var initializer = new AdminBootstrapInitializer(appUserMapper, passwordEncoder, properties);

        assertThatThrownBy(initializer::initialize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOOTSTRAP_ADMIN");
        verifyNoInteractions(appUserMapper);
    }
}
