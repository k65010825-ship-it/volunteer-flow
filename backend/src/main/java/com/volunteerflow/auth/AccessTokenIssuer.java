package com.volunteerflow.auth;

@FunctionalInterface
public interface AccessTokenIssuer {
    String issue(AppUser user);
}
