package com.volunteerflow.infrastructure.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("acceptance")
public class AcceptanceDatabaseGuard {
  @Bean
  static Guard acceptanceDatasourceGuard(Environment environment) {
    // Dedicated Flyway connections are not Spring DataSource beans and can bypass the guard.
    for (String key :
        new String[] {"url", "user", "password", "schemas", "default-schema", "init-sqls"}) {
      if (!environment.getProperty("spring.flyway." + key, "").isBlank()) {
        throw new BeanCreationException(
            "acceptanceDatasourceGuard",
            "Acceptance requires Flyway to use only the guarded DataSource");
      }
    }
    return new Guard(environment);
  }

  public static final class Guard implements BeanPostProcessor {
    private final Environment environment;
    private volatile String database;

    Guard(Environment environment) {
      this.environment = environment;
    }

    /**
     * A DataSource cannot be injected into Flyway until this post-initialization check succeeds.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
      if (!(bean instanceof DataSource source)) return bean;
      String expected = environment.getProperty("volunteerflow.acceptance.database", "");
      if (!expected.matches("vf_acceptance_[0-9a-f]{32}")
          || !"false".equalsIgnoreCase(environment.getProperty("volunteerflow.maintenance.enabled"))
          || !"false"
              .equalsIgnoreCase(
                  environment.getProperty("volunteerflow.events.consumer-auto-startup")))
        throw new BeanCreationException(beanName, "Acceptance isolation settings are invalid");
      try (Connection connection = source.getConnection()) {
        connection.setReadOnly(true);
        try (Statement statement = connection.createStatement()) {
          statement.setQueryTimeout(5);
          try (ResultSet rows = statement.executeQuery("SELECT DATABASE()")) {
            if (!rows.next() || !expected.equals(rows.getString(1)))
              throw new IllegalStateException();
          }
        }
        database = expected;
      } catch (Exception failure) {
        // Do not attach JDBC exception causes: they may contain URL, credential, or configuration.
        throw new BeanCreationException(beanName, "Acceptance database identity check failed");
      }
      return bean;
    }

    public String database() {
      if (database == null)
        throw new IllegalStateException("Acceptance DataSource has not been verified");
      return database;
    }

    public boolean maintenanceDisabled() {
      return "false".equalsIgnoreCase(environment.getProperty("volunteerflow.maintenance.enabled"));
    }

    public boolean consumerDisabled() {
      return "false"
          .equalsIgnoreCase(environment.getProperty("volunteerflow.events.consumer-auto-startup"));
    }
  }
}
