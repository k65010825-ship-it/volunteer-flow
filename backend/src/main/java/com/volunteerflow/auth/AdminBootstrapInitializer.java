package com.volunteerflow.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

public class AdminBootstrapInitializer {
    private final AppUserMapper appUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdminBootstrapProperties properties;

    public AdminBootstrapInitializer(AppUserMapper appUserMapper, PasswordEncoder passwordEncoder,
                                     AdminBootstrapProperties properties) {
        this.appUserMapper = appUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Transactional
    public void initialize() {
        if (properties.isAbsent()) return;
        if (!properties.isComplete()) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_* values must be either all present or all absent");
        }
        Long count = appUserMapper.selectCount(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getPlatformRole, "PLATFORM_ADMIN"));
        if (count > 0) return;

        AppUser admin = new AppUser();
        admin.setUsername(properties.username().trim());
        admin.setPasswordHash(passwordEncoder.encode(properties.password()));
        admin.setRealName(properties.realName().trim());
        admin.setStudentNumber(properties.studentNumber().trim());
        admin.setContact(properties.contact().trim());
        admin.setStatus("ACTIVE");
        admin.setPlatformRole("PLATFORM_ADMIN");
        admin.setVersion(0);
        appUserMapper.insert(admin);
    }
}
