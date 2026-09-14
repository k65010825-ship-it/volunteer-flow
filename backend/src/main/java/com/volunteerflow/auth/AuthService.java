package com.volunteerflow.auth;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.volunteerflow.infrastructure.web.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

@Service
@Profile("!test")
public class AuthService {
    private final AppUserMapper userMapper;
    private final RefreshSessionMapper sessionMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final Clock clock;

    public AuthService(AppUserMapper userMapper, RefreshSessionMapper sessionMapper, PasswordEncoder passwordEncoder,
                       AccessTokenIssuer accessTokenIssuer, RefreshTokenGenerator refreshTokenGenerator, Clock clock) {
        this.userMapper = userMapper;
        this.sessionMapper = sessionMapper;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.clock = clock;
    }

    @Transactional
    public AuthTokens register(RegisterRequest request, String deviceName) {
        if (userMapper.selectCount(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, request.username())) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_ALREADY_EXISTS", "Username is already registered");
        }
        if (userMapper.selectCount(Wrappers.<AppUser>lambdaQuery().eq(AppUser::getStudentNumber, request.studentNumber())) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "STUDENT_NUMBER_ALREADY_EXISTS", "Student number is already registered");
        }
        AppUser user = new AppUser();
        user.setId(IdWorker.getId());
        user.setUsername(request.username().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRealName(request.realName().trim());
        user.setStudentNumber(request.studentNumber().trim());
        user.setContact(request.contact().trim());
        user.setStatus("ACTIVE");
        user.setPlatformRole("USER");
        user.setVersion(0);
        userMapper.insert(user);
        return createTokens(user, deviceName);
    }

    @Transactional
    public AuthTokens login(LoginRequest request, String deviceName) {
        AppUser user = userMapper.selectOne(Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getUsername, request.username()).last("LIMIT 1"));
        if (user == null || !"ACTIVE".equals(user.getStatus())
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return createTokens(user, deviceName);
    }

    private AuthTokens createTokens(AppUser user, String deviceName) {
        Instant now = clock.instant();
        String refreshToken = refreshTokenGenerator.generate();
        RefreshSession session = new RefreshSession();
        session.setId(IdWorker.getId());
        session.setUserId(user.getId());
        session.setTokenHash(hash(refreshToken));
        session.setDeviceName(deviceName);
        session.setExpiresAt(LocalDateTime.ofInstant(now.plus(30, ChronoUnit.DAYS), ZoneOffset.UTC));
        sessionMapper.insert(session);
        return new AuthTokens(accessTokenIssuer.issue(user), refreshToken, now.plus(30, ChronoUnit.MINUTES));
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Username or password is incorrect");
    }
}
