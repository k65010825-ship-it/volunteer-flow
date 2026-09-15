package com.volunteerflow.infrastructure.security;

import com.volunteerflow.auth.AccessTokenIssuer;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.IssuedAccessToken;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

public class JwtTokenService implements AccessTokenIssuer {
  private final JwtEncoder encoder;
  private final JwtProperties properties;
  private final Clock clock;

  public JwtTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
    this.encoder = encoder;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public IssuedAccessToken issue(AppUser user) {
    Instant issuedAt = clock.instant();
    Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(properties.getIssuer())
            .subject(user.getId().toString())
            .audience(List.of(properties.getAudience()))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("username", user.getUsername())
            .claim("platform_role", user.getPlatformRole())
            .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedAccessToken(value, expiresAt);
  }
}
