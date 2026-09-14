package com.volunteerflow.organization;
import jakarta.validation.constraints.*; import java.time.Instant;
public record CreateInviteRequest(@Min(1) @Max(1000) Integer maxUses, @Future Instant expiresAt) { }
