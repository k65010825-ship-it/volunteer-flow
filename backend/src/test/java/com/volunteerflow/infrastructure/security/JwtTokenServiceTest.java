package com.volunteerflow.infrastructure.security;

import com.volunteerflow.auth.AppUser;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTest {
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void issuedTokenContainsIdentityAndCanBeVerified() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var components = components(Clock.fixed(now, ZoneOffset.UTC));
        AppUser user = user();

        var issuedToken = components.service().issue(user);
        String token = issuedToken.value();
        var jwt = components.decoder().decode(token);

        assertThat(jwt.getSubject()).isEqualTo("1");
        assertThat(jwt.getClaimAsString("username")).isEqualTo("student1");
        assertThat(jwt.getClaimAsString("platform_role")).isEqualTo("USER");
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
        assertThat(issuedToken.expiresAt()).isEqualTo(jwt.getExpiresAt());
    }

    @Test
    void tamperedAndExpiredTokensAreRejected() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var valid = components(Clock.fixed(now, ZoneOffset.UTC));
        String token = valid.service().issue(user()).value();
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> valid.decoder().decode(tampered)).isInstanceOf(JwtException.class);

        var expired = components(Clock.fixed(now.minus(Duration.ofHours(1)), ZoneOffset.UTC));
        assertThatThrownBy(() -> expired.decoder().decode(expired.service().issue(user()).value()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenWithWrongAudienceIsRejected() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var key = new SecretKeySpec(SECRET, "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtProperties issuerProperties = properties();
        issuerProperties.setAudience("another-client");
        String token = new JwtTokenService(encoder, issuerProperties, Clock.fixed(now, ZoneOffset.UTC)).issue(user()).value();

        var decoder = new JwtInfrastructureConfig().jwtDecoder(key, properties());

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    private Components components(Clock clock) {
        var key = new SecretKeySpec(SECRET, "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        JwtProperties properties = properties();
        var decoder = new JwtInfrastructureConfig().jwtDecoder(key, properties);
        return new Components(new JwtTokenService(encoder, properties, clock), decoder);
    }

    private JwtProperties properties() {
        JwtProperties properties = new JwtProperties();
        properties.setIssuer("volunteerflow");
        properties.setAudience("volunteerflow-web");
        properties.setAccessTokenTtl(Duration.ofMinutes(30));
        return properties;
    }

    private AppUser user() {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("student1");
        user.setPlatformRole("USER");
        return user;
    }

    private record Components(JwtTokenService service, org.springframework.security.oauth2.jwt.JwtDecoder decoder) { }
}
