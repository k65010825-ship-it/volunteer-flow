package com.volunteerflow.checkin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class SecureCheckinCodeGenerator implements CheckinCodeGenerator {
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private final SecureRandom random;

  public SecureCheckinCodeGenerator(SecureRandom random) {
    this.random = Objects.requireNonNull(random, "random");
  }

  /** One injectable singleton, declared locally without changing infrastructure configuration. */
  @Bean
  public static SecureRandom checkinSecureRandom() {
    return new SecureRandom();
  }

  @Override
  public String generate() {
    char[] code = new char[8];
    for (int i = 0; i < code.length; i++) {
      code[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
    }
    return new String(code);
  }

  @Override
  public byte[] digest(String normalizedCode) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(normalizedCode.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  @Override
  public String normalize(String submittedCode) {
    return submittedCode == null ? "" : submittedCode.trim().toUpperCase(Locale.ROOT);
  }
}
