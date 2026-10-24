package com.volunteerflow.infrastructure.web;

import com.volunteerflow.infrastructure.persistence.AcceptanceDatabaseGuard.Guard;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Non-secret identity proof for the opt-in acceptance profile, before the runner writes anything.
 */
@RestController
@Profile("acceptance")
public class AcceptanceIdentityController {
  private final Guard guard;

  public AcceptanceIdentityController(Guard guard, DataSource source) {
    this.guard = guard;
    Objects.requireNonNull(
        source); // Forces the guarded DataSource to initialize before this endpoint.
  }

  @GetMapping("/api/v1/acceptance/identity")
  public ApiResponse<Identity> identity() {
    return ApiResponse.of(
        new Identity(guard.database(), guard.maintenanceDisabled(), guard.consumerDisabled()));
  }

  public record Identity(String database, boolean maintenanceDisabled, boolean consumerDisabled) {}
}
