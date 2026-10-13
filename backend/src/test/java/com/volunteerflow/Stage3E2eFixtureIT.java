package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.volunteerflow.activity.ActivityService;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.checkin.ActivityFinalizationJob;
import com.volunteerflow.organization.CreateInviteRequest;
import com.volunteerflow.organization.OrganizationService;
import com.volunteerflow.registration.PromotionExpiryJob;
import com.volunteerflow.registration.RegistrationReviewService;
import com.volunteerflow.registration.RegistrationSubmissionService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Explicit seed/cleanup fixture for the public Nginx Stage 3 browser test.
 *
 * <p>The run ID is the only cross-process identity. Both actions rediscover rows by the exact
 * {@code s3e2e-<uuid>} prefix and exact derived usernames, so cleanup remains possible after the
 * seed JVM exits or after a partially failed browser run.
 */
@SpringBootTest(
    properties = {
      "volunteerflow.bootstrap-admin.username=",
      "spring.flyway.enabled=false"
    })
class Stage3E2eFixtureIT {

  private static final List<String> ORGANIZATION_TABLES_CHILD_FIRST =
      List.of(
          "outbox_event",
          "checkin_record",
          "checkin_session",
          "promotion_offer",
          "notification",
          "registration_answer",
          "registration_cycle",
          "registration",
          "activity_position_question",
          "activity_question",
          "activity_change",
          "activity_position",
          "activity",
          "organization_invite",
          "organization_member",
          "rbac_role_permission",
          "rbac_role",
          "audit_log");

  private final AppUserMapper users;
  private final OrganizationService organizations;
  private final ActivityService activities;
  private final RegistrationSubmissionService submissions;
  private final RegistrationReviewService reviews;
  private final PasswordEncoder passwords;
  private final JdbcTemplate sql;
  private final Clock clock;

  @MockitoBean private ActivityFinalizationJob automaticActivityFinalizer;
  @MockitoBean private PromotionExpiryJob automaticPromotionExpiry;

  @Autowired
  Stage3E2eFixtureIT(
      AppUserMapper users,
      OrganizationService organizations,
      ActivityService activities,
      RegistrationSubmissionService submissions,
      RegistrationReviewService reviews,
      PasswordEncoder passwords,
      JdbcTemplate sql,
      Clock clock) {
    this.users = users;
    this.organizations = organizations;
    this.activities = activities;
    this.submissions = submissions;
    this.reviews = reviews;
    this.passwords = passwords;
    this.sql = sql;
    this.clock = clock;
  }

  @Test
  void seedOrCleanExactRunFixture() {
    String runId = validatedRunId(System.getProperty("stage3.e2e.run-id"));
    String action = validatedAction(System.getProperty("stage3.e2e.action"));
    String prefix = "s3e2e-" + runId;

    if ("seed".equals(action)) {
      seed(prefix, runId);
    } else {
      cleanup(prefix);
    }
  }

  static String validatedRunId(String candidate) {
    if (candidate == null || candidate.isBlank()) {
      throw new IllegalArgumentException("stage3.e2e.run-id must be a canonical UUID");
    }
    String value = candidate.trim();
    UUID parsed;
    try {
      parsed = UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("stage3.e2e.run-id must be a canonical UUID", exception);
    }
    if (!parsed.toString().equals(value)) {
      throw new IllegalArgumentException("stage3.e2e.run-id must be a lowercase canonical UUID");
    }
    return value;
  }

  private static String validatedAction(String candidate) {
    if (candidate == null) {
      throw new IllegalArgumentException("stage3.e2e.action must be seed or cleanup");
    }
    String value = candidate.trim().toLowerCase(Locale.ROOT);
    if (!List.of("seed", "cleanup").contains(value)) {
      throw new IllegalArgumentException("stage3.e2e.action must be seed or cleanup");
    }
    return value;
  }

  private void seed(String prefix, String runId) {
    assertSchemaReady();
    assertThat(count("SELECT COUNT(*) FROM organization WHERE name=?", prefix)).isZero();
    for (String username : usernames(prefix)) {
      assertThat(count("SELECT COUNT(*) FROM app_user WHERE username=?", username)).isZero();
    }

    AppUser administrator = createUser(prefix, "admin", "E2E Administrator", "PLATFORM_ADMIN", runId);
    AppUser firstMember = createUser(prefix, "member1", "E2E Member One", "USER", runId);
    AppUser secondMember = createUser(prefix, "member2", "E2E Member Two", "USER", runId);

    var organization =
        organizations.create(
            administrator.getId(),
            administrator.getPlatformRole(),
            new OrganizationService.CreateOrganizationRequest(
                prefix, "Isolated Stage 3 browser fixture", administrator.getId()));
    var invitation =
        organizations.createInvite(
            administrator.getId(),
            organization.getId(),
            new CreateInviteRequest(2, Instant.now(clock).plus(1, ChronoUnit.DAYS)));
    organizations.joinByInvite(firstMember.getId(), invitation.code());
    organizations.joinByInvite(secondMember.getId(), invitation.code());

    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    var activity =
        activities.create(
            administrator.getId(),
            organization.getId(),
            new ActivityService.ActivityRequest(
                prefix + "-activity",
                "Stage 3 browser notification and check-in flow",
                "E2E assembly point",
                now.minusHours(1),
                now.plusDays(1),
                now.plusDays(2),
                now.plusDays(3),
                now.plusDays(3).plusHours(2)));
    var position =
        activities.addPosition(
            administrator.getId(),
            activity.getId(),
            new ActivityService.PositionRequest(
                prefix + "-position",
                "Review-mode browser fixture position",
                2,
                "REVIEW",
                30,
                null,
                null,
                "E2E assembly point"));
    activities.publish(administrator.getId(), activity.getId());

    for (AppUser member : List.of(firstMember, secondMember)) {
      var registration =
          submissions.submit(
              member.getId(),
              activity.getId(),
              new RegistrationSubmissionService.SubmitRegistrationRequest(
                  position.getId(), List.of()));
      reviews.decide(
          administrator.getId(),
          registration.registrationId(),
          new RegistrationReviewService.ReviewDecisionRequest(
              "CONFIRM", "Stage 3 browser fixture approval"));
    }

    assertThat(count("SELECT COUNT(*) FROM organization WHERE id=? AND name=?", organization.getId(), prefix))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM registration_cycle WHERE organization_id=?"
                    + " AND status='CONFIRMED' AND attendance_status='PENDING'",
                organization.getId()))
        .isEqualTo(2);
    System.out.println("Stage 3 E2E fixture seeded for exact run prefix " + prefix + ".");
  }

  private AppUser createUser(
      String prefix, String suffix, String realName, String platformRole, String runId) {
    AppUser user = new AppUser();
    user.setId(IdWorker.getId());
    user.setUsername(prefix + "-" + suffix);
    user.setPasswordHash(passwords.encode(passwordFor(runId)));
    user.setRealName(realName);
    user.setStudentNumber(prefix + "-" + suffix);
    user.setContact("stage3-e2e-only");
    user.setStatus("ACTIVE");
    user.setPlatformRole(platformRole);
    user.setVersion(0);
    users.insert(user);
    return user;
  }

  private static String passwordFor(String runId) {
    return "S3!" + runId.replace("-", "").substring(0, 12) + "aZ9";
  }

  private void cleanup(String prefix) {
    List<Long> organizationIds =
        sql.queryForList("SELECT id FROM organization WHERE name=?", Long.class, prefix);
    List<String> exactUsernames = usernames(prefix);
    List<Long> userIds =
        exactUsernames.stream()
            .flatMap(
                username ->
                    sql.queryForList(
                            "SELECT id FROM app_user WHERE username=?", Long.class, username)
                        .stream())
            .toList();

    for (Long organizationId : organizationIds) {
      for (String table : ORGANIZATION_TABLES_CHILD_FIRST) {
        sql.update("DELETE FROM " + table + " WHERE organization_id=?", organizationId);
        assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE organization_id=?", organizationId))
            .as(table + " cleanup for " + prefix)
            .isZero();
      }
      sql.update("DELETE FROM organization WHERE id=? AND name=?", organizationId, prefix);
      assertThat(count("SELECT COUNT(*) FROM organization WHERE id=?", organizationId)).isZero();
    }

    for (int index = 0; index < exactUsernames.size(); index++) {
      String username = exactUsernames.get(index);
      List<Long> matchingIds =
          sql.queryForList("SELECT id FROM app_user WHERE username=?", Long.class, username);
      for (Long userId : matchingIds) {
        sql.update("DELETE FROM refresh_session WHERE user_id=?", userId);
        assertThat(count("SELECT COUNT(*) FROM refresh_session WHERE user_id=?", userId)).isZero();
        sql.update("DELETE FROM app_user WHERE id=? AND username=?", userId, username);
        assertThat(count("SELECT COUNT(*) FROM app_user WHERE id=?", userId)).isZero();
      }
    }

    assertThat(count("SELECT COUNT(*) FROM organization WHERE name=?", prefix)).isZero();
    for (String username : exactUsernames) {
      assertThat(count("SELECT COUNT(*) FROM app_user WHERE username=?", username)).isZero();
    }
    assertThat(userIds).doesNotContainNull();
    System.out.println("Stage 3 E2E fixture cleanup verified zero rows for exact run prefix " + prefix + ".");
  }

  private void assertSchemaReady() {
    assertThat(sql.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("volunteer_flow");
    assertThat(
            count(
                "SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema=DATABASE() AND table_name='checkin_session'"))
        .isEqualTo(1);
  }

  private static List<String> usernames(String prefix) {
    return List.of(prefix + "-admin", prefix + "-member1", prefix + "-member2");
  }

  private long count(String statement, Object... arguments) {
    return sql.queryForObject(statement, Long.class, arguments);
  }
}
