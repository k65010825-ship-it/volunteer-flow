package com.volunteerflow.auth;

import com.volunteerflow.infrastructure.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService authService;
    private final boolean secureCookie;

    public AuthController(AuthService authService,
                          @Value("${volunteerflow.auth.secure-cookie:false}") boolean secureCookie) {
        this.authService = authService;
        this.secureCookie = secureCookie;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            @RequestHeader(value = "User-Agent", required = false) String deviceName) {
        return response(authService.register(request, deviceName), HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> login(
            @Valid @RequestBody LoginRequest request,
            @RequestHeader(value = "User-Agent", required = false) String deviceName) {
        return response(authService.login(request, deviceName), HttpStatus.OK);
    }

    private ResponseEntity<ApiResponse<AccessTokenResponse>> response(AuthTokens tokens, HttpStatus status) {
        ResponseCookie cookie = ResponseCookie.from("refresh_token", tokens.refreshToken())
                .httpOnly(true).secure(secureCookie).sameSite("Lax")
                .path("/api/v1/auth").maxAge(Duration.ofDays(30)).build();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.of(new AccessTokenResponse(tokens.accessToken(), tokens.accessTokenExpiresAt())));
    }

    public record AccessTokenResponse(String accessToken, Instant expiresAt) { }
}
