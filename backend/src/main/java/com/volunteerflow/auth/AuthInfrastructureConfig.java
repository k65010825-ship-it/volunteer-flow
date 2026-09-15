package com.volunteerflow.auth;

import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminBootstrapProperties.class)
public class AuthInfrastructureConfig {
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  public Clock authClock() {
    return Clock.systemUTC();
  }

  @Bean
  public SecureTokenGenerator secureTokenGenerator() {
    return new SecureTokenGenerator();
  }

  @Bean
  @Profile("!test")
  public AdminBootstrapInitializer adminBootstrapInitializer(
      AppUserMapper mapper, PasswordEncoder encoder, AdminBootstrapProperties properties) {
    return new AdminBootstrapInitializer(mapper, encoder, properties);
  }

  @Bean
  @Profile("!test")
  public ApplicationRunner adminBootstrapRunner(AdminBootstrapInitializer initializer) {
    return args -> initializer.initialize();
  }
}
