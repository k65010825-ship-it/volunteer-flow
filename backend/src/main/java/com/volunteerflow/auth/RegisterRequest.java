package com.volunteerflow.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank @Size(min = 4, max = 64) String username,
    @NotBlank @Size(min = 12, max = 72) String password,
    @NotBlank @Size(max = 64) String realName,
    @NotBlank @Size(max = 64) String studentNumber,
    @NotBlank @Size(max = 128) String contact) {}
