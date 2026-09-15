package com.volunteerflow.auth;

@FunctionalInterface
public interface RefreshTokenGenerator {
  String generate();
}
