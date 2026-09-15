package com.volunteerflow.auth;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.volunteerflow.infrastructure.web.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements credential login and database-backed refresh-session rotation. */
@Service
@Profile("!test")
public class AuthService {
  private final AppUserMapper userMapper;
  private final RefreshSessionMapper sessionMapper;
  private final PasswordEncoder passwordEncoder;
  private final AccessTokenIssuer accessTokenIssuer;
  private final RefreshTokenGenerator refreshTokenGenerator;
  private final Clock clock;

  public AuthService(
      AppUserMapper userMapper,
      RefreshSessionMapper sessionMapper,
      PasswordEncoder passwordEncoder,
      AccessTokenIssuer accessTokenIssuer,
      RefreshTokenGenerator refreshTokenGenerator,
      Clock clock) {
    this.userMapper = userMapper;
    this.sessionMapper = sessionMapper;
    this.passwordEncoder = passwordEncoder;
    this.accessTokenIssuer = accessTokenIssuer;
    this.refreshTokenGenerator = refreshTokenGenerator;
    this.clock = clock;
  }

  @Transactional
  public AuthTokens register(RegisterRequest request, String deviceName) {
    if (userMapper.selectCount(
            Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, request.username()))
        > 0) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "USERNAME_ALREADY_EXISTS", "Username is already registered");
    }
    if (userMapper.selectCount(
            Wrappers.<AppUser>lambdaQuery().eq(AppUser::getStudentNumber, request.studentNumber()))
        > 0) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "STUDENT_NUMBER_ALREADY_EXISTS",
          "Student number is already registered");
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
    AppUser user =
        userMapper.selectOne(
            Wrappers.<AppUser>lambdaQuery()
                .eq(AppUser::getUsername, request.username())
                .last("LIMIT 1"));
    if (user == null
        || !"ACTIVE".equals(user.getStatus())
        || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw invalidCredentials();
    }
    return createTokens(user, deviceName);
  }

  @Transactional
  public AuthTokens refresh(String rawRefreshToken, String deviceName) {
    // The row lock prevents two concurrent refreshes from successfully rotating the same token.
    RefreshSession session = findUsableSession(rawRefreshToken);
    AppUser user = userMapper.selectById(session.getUserId());
    if (user == null || !"ACTIVE".equals(user.getStatus())) {
      throw invalidRefreshToken();
    }

    String nextRefreshToken = refreshTokenGenerator.generate();
    Instant now = clock.instant();
    session.setTokenHash(hash(nextRefreshToken));
    session.setDeviceName(normalizeDeviceName(deviceName));
    session.setExpiresAt(LocalDateTime.ofInstant(now.plus(30, ChronoUnit.DAYS), ZoneOffset.UTC));
    session.setLastUsedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    sessionMapper.updateById(session);
    IssuedAccessToken accessToken = accessTokenIssuer.issue(user);
    return new AuthTokens(accessToken.value(), nextRefreshToken, accessToken.expiresAt());
  }

  @Transactional
  public void logout(Long currentUserId, String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      return;
    }
    RefreshSession session = sessionMapper.selectByTokenHashForUpdate(hash(rawRefreshToken));
    if (session != null
        && session.getUserId().equals(currentUserId)
        && session.getRevokedAt() == null) {
      session.setRevokedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
      sessionMapper.updateById(session);
    }
  }

  @Transactional(readOnly = true)
  public CurrentUser me(Long userId) {
    AppUser user = userMapper.selectById(userId);
    if (user == null || !"ACTIVE".equals(user.getStatus())) {
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "User is unavailable");
    }
    return CurrentUser.from(user);
  }

  private AuthTokens createTokens(AppUser user, String deviceName) {
    Instant now = clock.instant();
    String refreshToken = refreshTokenGenerator.generate();
    RefreshSession session = new RefreshSession();
    session.setId(IdWorker.getId());
    session.setUserId(user.getId());
    session.setTokenHash(hash(refreshToken));
    session.setDeviceName(normalizeDeviceName(deviceName));
    session.setExpiresAt(LocalDateTime.ofInstant(now.plus(30, ChronoUnit.DAYS), ZoneOffset.UTC));
    sessionMapper.insert(session);
    IssuedAccessToken accessToken = accessTokenIssuer.issue(user);
    return new AuthTokens(accessToken.value(), refreshToken, accessToken.expiresAt());
  }

  static String hash(String token) {
    try {
      // Refresh tokens are bearer credentials, so only their digest is persisted.
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private RefreshSession findUsableSession(String rawRefreshToken) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      throw invalidRefreshToken();
    }
    RefreshSession session = sessionMapper.selectByTokenHashForUpdate(hash(rawRefreshToken));
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    if (session == null || session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now)) {
      throw invalidRefreshToken();
    }
    return session;
  }

  private static BusinessException invalidCredentials() {
    return new BusinessException(
        HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Username or password is incorrect");
  }

  private static BusinessException invalidRefreshToken() {
    return new BusinessException(
        HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
  }

  private static String normalizeDeviceName(String deviceName) {
    if (deviceName == null) {
      return null;
    }
    String normalized = deviceName.trim();
    return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
  }
}
