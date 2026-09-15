package com.volunteerflow.auth;

import java.time.Instant;

public record IssuedAccessToken(String value, Instant expiresAt) {}
