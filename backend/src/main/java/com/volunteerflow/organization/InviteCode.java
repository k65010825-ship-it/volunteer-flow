package com.volunteerflow.organization;

import java.time.Instant;

public record InviteCode(String code, Instant expiresAt, int maxUses) {}
