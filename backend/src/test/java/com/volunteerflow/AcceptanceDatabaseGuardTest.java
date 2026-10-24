package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.volunteerflow.infrastructure.persistence.AcceptanceDatabaseGuard;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

class AcceptanceDatabaseGuardTest {
  private static final String TARGET = "vf_acceptance_0123456789abcdef0123456789abcdef";
  private static final AtomicBoolean MIGRATION = new AtomicBoolean();
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(AcceptanceDatabaseGuard.class, Fixture.class)
          .withPropertyValues(
              "spring.profiles.active=acceptance",
              "volunteerflow.acceptance.database=" + TARGET,
              "volunteerflow.maintenance.enabled=false",
              "volunteerflow.events.consumer-auto-startup=false");

  @Test
  void rejectsOriginalDatabaseBeforeFlywayInitializerExecutes() {
    MIGRATION.set(false);
    runner
        .withPropertyValues("fixture.database=volunteer_flow")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(MIGRATION).isFalse();
            });
  }

  @Test
  void acceptsExactIsolatedDatabaseBeforeFlywayInitializerExecutes() {
    MIGRATION.set(false);
    runner
        .withPropertyValues("fixture.database=" + TARGET)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(MIGRATION).isTrue();
              assertThat(context.getBean(AcceptanceDatabaseGuard.Guard.class).database())
                  .isEqualTo(TARGET);
            });
  }

  @Test
  void rejectsNonUuidTargetEvenWhenDatasourceMatchesIt() {
    MIGRATION.set(false);
    runner
        .withPropertyValues(
            "fixture.database=volunteer_flow", "volunteerflow.acceptance.database=volunteer_flow")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(MIGRATION).isFalse();
            });
  }

  @Test
  void rejectsEnabledMaintenanceBeforeMigration() {
    MIGRATION.set(false);
    runner
        .withPropertyValues("fixture.database=" + TARGET, "volunteerflow.maintenance.enabled=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(MIGRATION).isFalse();
            });
  }

  @Test
  void rejectsEnabledKafkaConsumerBeforeMigration() {
    MIGRATION.set(false);
    runner
        .withPropertyValues(
            "fixture.database=" + TARGET, "volunteerflow.events.consumer-auto-startup=true")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(MIGRATION).isFalse();
            });
  }

  @Test
  void rejectsAnotherValidUuidDatabaseBeforeMigration() {
    MIGRATION.set(false);
    runner
        .withPropertyValues("fixture.database=vf_acceptance_ffffffffffffffffffffffffffffffff")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(MIGRATION).isFalse();
            });
  }

  @Test
  void rejectsDedicatedFlywayConnectionBeforeMigration() {
    for (String key : new String[] {"url", "user", "password", "schemas", "default-schema"}) {
      MIGRATION.set(false);
      runner
          .withPropertyValues(
              "fixture.database=" + TARGET, "spring.flyway." + key + "=forbidden-override")
          .run(
              context -> {
                assertThat(context).hasFailed();
                assertThat(MIGRATION).isFalse();
              });
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class Fixture {
    @Bean
    DataSource dataSource(Environment environment) throws SQLException {
      DataSource source = mock(DataSource.class);
      Connection connection = mock(Connection.class);
      Statement statement = mock(Statement.class);
      ResultSet result = mock(ResultSet.class);
      when(source.getConnection()).thenReturn(connection);
      when(connection.createStatement()).thenReturn(statement);
      when(statement.executeQuery("SELECT DATABASE()")).thenReturn(result);
      when(result.next()).thenReturn(true);
      when(result.getString(1)).thenReturn(environment.getProperty("fixture.database"));
      return source;
    }

    @Bean
    FlywayMigrationInitializer flywayInitializer(DataSource source) {
      return new FlywayMigrationInitializer(mock(Flyway.class), ignored -> MIGRATION.set(true));
    }
  }
}
