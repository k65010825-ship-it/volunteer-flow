package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Explicit opt-in post-upgrade startup/read-only proof, without jobs, bootstrap writes or Kafka.
 */
@SpringBootTest(
    properties = {
      "spring.flyway.enabled=true",
      "spring.flyway.target=10",
      "spring.flyway.clean-disabled=true",
      "volunteerflow.maintenance.enabled=false",
      "volunteerflow.events.consumer-auto-startup=false",
      "volunteerflow.rate-limit.enabled=false",
      "volunteerflow.bootstrap-admin.username="
    })
@ActiveProfiles("original-readonly-verification")
class OriginalDatabaseReadinessIT {
  @Autowired DataSource datasource;
  @Autowired Flyway flyway;
  @Autowired HealthEndpoint health;

  @Test
  void canonicalMigrationValidationAndOriginalRowsRemainIntact() throws Exception {
    try (Connection connection = datasource.getConnection()) {
      connection.setReadOnly(true);
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT DATABASE()")) {
        rows.next();
        assertThat(rows.getString(1)).isEqualTo("volunteer_flow");
      }
      assertThat(
              count(
                  connection,
                  "SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('9','10') AND"
                      + " success=1"))
          .isEqualTo(2);
      assertThat(count(connection, "SELECT COUNT(*) FROM flyway_schema_history WHERE success=0"))
          .isZero();
      assertThat(count(connection, "SELECT COUNT(*) FROM app_user")).isEqualTo(112);
      assertThat(count(connection, "SELECT COUNT(*) FROM registration_cycle")).isEqualTo(15);
      assertThat(count(connection, "SELECT COUNT(*) FROM notification")).isEqualTo(3);
      assertThat(count(connection, "SELECT COUNT(*) FROM outbox_event")).isZero();
    }
    assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
  }

  @Test
  void readinessIsUpWithoutStartingAKafkaBroker() {
    assertThat(health.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
  }

  static long count(Connection connection, String query) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(query)) {
      rows.next();
      return rows.getLong(1);
    }
  }
}
