package com.volunteerflow.auth;

import java.time.Instant;

public record AuthTokens(String accessToken, String refreshToken, Instant accessTokenExpiresAt) {}
