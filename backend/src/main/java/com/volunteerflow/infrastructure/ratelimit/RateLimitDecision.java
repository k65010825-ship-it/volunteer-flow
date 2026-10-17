package com.volunteerflow.infrastructure.ratelimit;

public record RateLimitDecision(boolean allowed, long retryAfterSeconds) {}
