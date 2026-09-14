package com.volunteerflow.auth;

@FunctionalInterface
public interface AccessTokenIssuer {
    IssuedAccessToken issue(AppUser user);
}
