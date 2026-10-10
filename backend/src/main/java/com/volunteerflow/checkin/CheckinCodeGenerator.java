package com.volunteerflow.checkin;

public interface CheckinCodeGenerator {
  String generate();

  byte[] digest(String normalizedCode);

  String normalize(String submittedCode);
}
