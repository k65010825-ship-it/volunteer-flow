package com.volunteerflow.infrastructure.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<JwtDecoder> jwtDecoderProvider,
      ObjectProvider<DatabaseJwtAuthenticationConverter> converterProvider)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            exceptions ->
                exceptions.authenticationEntryPoint(
                    (request, response, exception) ->
                        response.sendError(HttpStatus.UNAUTHORIZED.value())))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        "/actuator/health",
                        "/actuator/health/**",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh")
                    .permitAll()
                    .anyRequest()
                    .authenticated());
    JwtDecoder jwtDecoder = jwtDecoderProvider.getIfAvailable();
    DatabaseJwtAuthenticationConverter converter = converterProvider.getIfAvailable();
    if (jwtDecoder != null && converter != null) {
      http.oauth2ResourceServer(
          resourceServer ->
              resourceServer.jwt(
                  jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(converter)));
    }
    return http.build();
  }
}
