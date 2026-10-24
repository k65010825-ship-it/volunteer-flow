package com.volunteerflow.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @NotBlank @Size(max = 64) String realName, @NotBlank @Size(max = 128) String contact) {}
