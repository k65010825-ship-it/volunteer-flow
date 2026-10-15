package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecureDynamicCheckinTokenGeneratorTest {
  @Test
  void generatesUnpaddedBase64UrlFromConfiguredEntropy() {
    String token = new SecureDynamicCheckinTokenGenerator().generate(24);

    assertThat(token).doesNotContain("=");
    assertThat(Base64.getUrlDecoder().decode(token)).hasSize(24);
  }
}
