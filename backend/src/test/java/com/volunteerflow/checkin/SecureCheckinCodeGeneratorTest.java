package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SecureCheckinCodeGeneratorTest {
  private final CheckinCodeGenerator generator = new SecureCheckinCodeGenerator(new SecureRandom());

  @Test
  void generatesOneThousandEightCharacterUnambiguousCodes() {
    for (int i = 0; i < 1000; i++) {
      assertThat(generator.generate()).matches("^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$");
    }
  }

  @Test
  void digestsLiteralAsSha256BytesSuitableForConstantTimeComparison() {
    byte[] expected = HexFormat.of().parseHex(
        "a00d76646eba91b057841554d5c8334f498dc592ed744bce404f21fe271cd36e");
    byte[] actual = generator.digest("ABCD2345");
    assertThat(actual).hasSize(32);
    assertThat(MessageDigest.isEqual(actual, expected)).isTrue();
    assertThat(MessageDigest.isEqual(actual, generator.digest("ABCD2346"))).isFalse();
  }

  @Test
  void normalizationTrimsAndUppercasesWithoutDependingOnDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      assertThat(generator.normalize("  abcd2345\t")).isEqualTo("ABCD2345");
      assertThat(generator.normalize(" i ")).isEqualTo("I");
      assertThat(generator.normalize("\t \n")).isEmpty();
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void normalizationTreatsMissingCodeAsEmptyForLaterValidation() {
    assertThat(generator.normalize(null)).isEmpty();
  }

  @Test
  void requiresInjectedSecureRandom() {
    assertThatThrownBy(() -> new SecureCheckinCodeGenerator(null))
        .isInstanceOf(NullPointerException.class);
  }
}
