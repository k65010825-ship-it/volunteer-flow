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
    requireValidNewPassword(request.password());
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
    // BCrypt accepts at most 72 bytes, not 72 Java characters. Keep login failures generic.
    if (request.password() == null
        || request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
      throw invalidCredentials();
    }
    // Serialize credential verification and session creation with password changes.
    AppUser user = userMapper.selectByUsernameForUpdate(request.username());
    if (user == null
        || !"ACTIVE".equals(user.getStatus())
        || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw invalidCredentials();
    }
    return createTokens(user, deviceName);
  }

  @Transactional
  public AuthTokens refresh(String rawRefreshToken, String deviceName) {
    if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
      throw invalidRefreshToken();
    }
    String tokenHash = hash(rawRefreshToken);
    // Discovery is non-locking. Every flow takes the user lock before any session write lock.
    RefreshSession discovered =
        sessionMapper.selectOne(
            Wrappers.<RefreshSession>lambdaQuery()
                .eq(RefreshSession::getTokenHash, tokenHash)
                .last("LIMIT 1"));
    if (discovered == null) {
      throw invalidRefreshToken();
    }
    AppUser user = userMapper.selectByIdForUpdate(discovered.getUserId());
    if (user == null || !"ACTIVE".equals(user.getStatus())) {
      throw invalidRefreshToken();
    }
    // Re-read current committed state after waiting for the user lock; never trust the snapshot.
    RefreshSession session = findUsableSession(rawRefreshToken);
    if (!user.getId().equals(session.getUserId()) || !tokenHash.equals(session.getTokenHash())) {
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

  @Transactional
  public CurrentUser updateProfile(Long userId, UpdateProfileRequest request) {
    AppUser user = requireActiveLockedUser(userId);
    String realName = request.realName() == null ? "" : request.realName().trim();
    String contact = request.contact() == null ? "" : request.contact().trim();
    if (realName.isBlank()
        || realName.length() > 64
        || contact.isBlank()
        || contact.length() > 128) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "INVALID_PROFILE",
          "Name and contact are required and must fit their length limits");
    }
    // Never persist a caller-controlled entity or rewrite credentials/role/version.
    userMapper.updateProfile(userId, realName, contact);
    user.setRealName(realName);
    user.setContact(contact);
    return CurrentUser.from(user);
  }

  @Transactional
  public void changePassword(Long userId, ChangePasswordRequest request) {
    AppUser user = requireActiveLockedUser(userId);
    String currentPassword = request.currentPassword();
    if (currentPassword == null
        || currentPassword.getBytes(StandardCharsets.UTF_8).length > 72
        || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_CURRENT_PASSWORD", "Current password is incorrect");
    }
    String newPassword = request.newPassword();
    requireValidNewPassword(newPassword);
    if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "Choose a different password");
    }
    int version = user.getVersion() == null ? 0 : user.getVersion();
    if (version == Integer.MAX_VALUE
        || userMapper.updatePassword(userId, passwordEncoder.encode(newPassword), version) != 1) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "CREDENTIALS_CHANGED",
          "Credentials changed; retry after signing in");
    }
    sessionMapper.revokeAllForUser(
        userId, LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
  }

  private AppUser requireActiveLockedUser(Long userId) {
    AppUser user = userMapper.selectByIdForUpdate(userId);
    if (user == null || !"ACTIVE".equals(user.getStatus())) {
      throw new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "User is unavailable");
    }
    return user;
  }

  private static void requireValidNewPassword(String password) {
    if (password == null
        || password.isBlank()
        || password.length() < 12
        || password.length() > 72
        || password.getBytes(StandardCharsets.UTF_8).length > 72) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "INVALID_NEW_PASSWORD",
          "New password must contain 12 to 72 characters and at most 72 UTF-8 bytes");
    }
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
