package com.volunteerflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class FlywayMigrationContractTest {

  private static final Path MIGRATION_DIRECTORY = Path.of("src/main/resources/db/migration");
  private static final List<String> DOMAIN_MIGRATIONS =
      List.of(
          "V2__create_account_tables.sql",
          "V3__create_organization_tables.sql",
          "V4__create_rbac_tables.sql",
          "V5__create_activity_tables.sql",
          "V6__create_registration_tables.sql",
          "V7__create_collaboration_tables.sql",
          "V8__create_outbox_table.sql");
  private static final Set<String> EXPECTED_TABLES =
      Set.of(
          "app_user",
          "refresh_session",
          "organization",
          "organization_invite",
          "organization_member",
          "rbac_permission",
          "rbac_role",
          "rbac_role_permission",
          "activity",
          "activity_position",
          "activity_change",
          "activity_question",
          "activity_position_question",
          "registration",
          "registration_cycle",
          "registration_answer",
          "promotion_offer",
          "checkin_session",
          "checkin_record",
          "notification",
          "audit_log",
          "outbox_event");
  private static final List<String> REQUIRED_UNIQUE_KEYS =
      List.of(
          "UNIQUE KEY uk_app_user_username",
          "UNIQUE KEY uk_app_user_student_number",
          "UNIQUE KEY uk_organization_member_org_user",
          "UNIQUE KEY uk_rbac_role_org_name",
          "UNIQUE KEY uk_rbac_role_permission_role_permission",
          "UNIQUE KEY uk_registration_activity_user",
          "UNIQUE KEY uk_registration_cycle_number",
          "UNIQUE KEY uk_registration_cycle_waitlist_sequence",
          "UNIQUE KEY uk_checkin_record_activity_user",
          "UNIQUE KEY uk_outbox_event_event_id");
  private static final Pattern CREATE_TABLE = Pattern.compile("(?i)CREATE\\s+TABLE\\s+([a-z_]+)");
  private static final Pattern PHYSICAL_REFERENCE =
      Pattern.compile(
          "(?i)\\bFOREIGN\\s+KEY\\b|\\bREFERENCES\\b|\\bON\\s+(?:DELETE|UPDATE)\\s+CASCADE\\b");

  @Test
  void domainMigrationsCreateApprovedSchemaWithoutPhysicalForeignKeys() throws IOException {
    StringBuilder allSql = new StringBuilder();

    for (String migration : DOMAIN_MIGRATIONS) {
      Path path = MIGRATION_DIRECTORY.resolve(migration);
      assertThat(path).as("migration %s", migration).exists();
      allSql.append(Files.readString(path, StandardCharsets.UTF_8)).append('\n');
    }

    Set<String> actualTables = new HashSet<>();
    Matcher matcher = CREATE_TABLE.matcher(allSql);
    while (matcher.find()) {
      actualTables.add(matcher.group(1).toLowerCase());
    }

    assertThat(actualTables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    assertThat(PHYSICAL_REFERENCE.matcher(allSql).find()).isFalse();
    assertThat(allSql.toString()).contains(REQUIRED_UNIQUE_KEYS.toArray(String[]::new));
  }

  @Test
  void stageThreeMigrationAddsAttendanceStateAndQueryIndexes() throws IOException {
    String v9 =
        Files.readString(
            MIGRATION_DIRECTORY.resolve("V9__add_stage3_notification_and_attendance_support.sql"),
            StandardCharsets.UTF_8);

    assertThat(v9).contains("attendance_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED'");
    assertThat(v9).contains("KEY idx_activity_status_end_id (status, activity_end_at, id)");
    assertThat(v9).contains("KEY idx_cycle_activity_status_id (activity_id, status, id)");
    assertThat(v9).contains("KEY idx_notification_user_created_id (user_id, create_time, id)");
    assertThat(v9).contains("KEY idx_checkin_session_activity_status_close_id");
  }

  @Test
  void stage5MigrationAddsLeasedOutboxAndConsumerIdempotency() throws IOException {
    String sql = stage5Sql();
    assertThat(sql).contains(
        "ALTER TABLE outbox_event",
        "topic VARCHAR(160) NOT NULL", "message_key VARCHAR(160) NOT NULL",
        "locked_by VARCHAR(128) NULL", "locked_until DATETIME(6) NULL",
        "last_error VARCHAR(1000) NULL", "published_partition INT NULL",
        "published_offset BIGINT NULL",
        "KEY idx_outbox_event_status_retry_id (status, next_retry_at, id)",
        "KEY idx_outbox_event_status_lease_id (status, locked_until, id)",
        "KEY idx_outbox_event_published_at_id (published_at, id)",
        "CREATE TABLE consumed_event", "id BIGINT NOT NULL",
        "consumer_name VARCHAR(128) NOT NULL", "event_id CHAR(36) NOT NULL",
        "processed_at DATETIME(6) NOT NULL",
        "UNIQUE KEY uk_consumed_event_consumer_event (consumer_name, event_id)");
    assertThat(PHYSICAL_REFERENCE.matcher(sql).find()).isFalse();
  }

  @Test
  void stage5MigrationAddsIndexedPromotionReconciliation() throws IOException {
    String sql = stage5Sql();
    assertThat(sql).contains(
        "CREATE TABLE promotion_reconcile_task", "position_id BIGINT NOT NULL",
        "organization_id BIGINT NOT NULL", "status VARCHAR(32) NOT NULL",
        "request_version BIGINT NOT NULL", "processed_version BIGINT NOT NULL",
        "next_run_at DATETIME(6) NOT NULL", "locked_by VARCHAR(128) NULL",
        "locked_until DATETIME(6) NULL", "attempt_count INT NOT NULL",
        "last_error VARCHAR(1000) NULL", "create_time DATETIME(6) NOT NULL",
        "update_time DATETIME(6) NOT NULL", "PRIMARY KEY (position_id)",
        "KEY idx_promotion_reconcile_status_run_position (status, next_run_at, position_id)");
    assertThat(sql).contains(
        "KEY idx_promotion_reconcile_status_lease_position (status, locked_until, position_id)");
    assertThat(PHYSICAL_REFERENCE.matcher(sql).find()).isFalse();
  }

  private String stage5Sql() throws IOException {
    Path path = MIGRATION_DIRECTORY.resolve("V10__add_stage5_event_pipeline.sql");
    assertThat(path).exists();
    return Files.readString(path, StandardCharsets.UTF_8);
  }
}
