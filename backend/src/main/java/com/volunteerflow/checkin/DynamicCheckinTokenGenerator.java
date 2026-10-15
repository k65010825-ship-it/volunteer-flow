package com.volunteerflow.checkin;

public interface DynamicCheckinTokenGenerator {
  String generate(int bytes);
}
