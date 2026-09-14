package com.volunteerflow.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationContractTest {

    private static final Path MIGRATION_DIRECTORY = Path.of("src/main/resources/db/migration");
    private static final List<String> DOMAIN_MIGRATIONS = List.of(
            "V2__create_account_tables.sql",
            "V3__create_organization_tables.sql",
            "V4__create_rbac_tables.sql",
            "V5__create_activity_tables.sql",
            "V6__create_registration_tables.sql",
            "V7__create_collaboration_tables.sql",
            "V8__create_outbox_table.sql");
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "app_user", "refresh_session", "organization", "organization_invite",
            "organization_member", "rbac_permission", "rbac_role", "rbac_role_permission",
            "activity", "activity_position", "activity_change", "activity_question",
            "activity_position_question", "registration", "registration_cycle",
            "registration_answer", "promotion_offer", "checkin_session", "checkin_record",
            "notification", "audit_log", "outbox_event");
    private static final List<String> REQUIRED_UNIQUE_KEYS = List.of(
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
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+([a-z_]+)");
    private static final Pattern PHYSICAL_REFERENCE = Pattern.compile(
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
}
