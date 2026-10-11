package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.node.TextNode;
import com.volunteerflow.activity.Activity;
import com.volunteerflow.activity.ActivityChangeService;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.activity.ActivityQuestionService;
import com.volunteerflow.activity.ActivityService;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.checkin.ActivityFinalizationJob;
import com.volunteerflow.checkin.ActivityFinalizationService;
import com.volunteerflow.checkin.AttendanceQueryService;
import com.volunteerflow.checkin.CheckinCodeGenerator;
import com.volunteerflow.checkin.CheckinService;
import com.volunteerflow.checkin.CheckinSessionService;
import com.volunteerflow.checkin.CheckinViews.AttendanceSummary;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.notification.NotificationService;
import com.volunteerflow.organization.CreateInviteRequest;
import com.volunteerflow.organization.OrganizationService;
import com.volunteerflow.registration.PromotionExpiryJob;
import com.volunteerflow.registration.PromotionExpiryService;
import com.volunteerflow.registration.PromotionOffer;
import com.volunteerflow.registration.PromotionOfferMapper;
import com.volunteerflow.registration.PromotionService;
import com.volunteerflow.registration.RegistrationCancellationService;
import com.volunteerflow.registration.RegistrationCycleMapper;
import com.volunteerflow.registration.RegistrationReviewService;
import com.volunteerflow.registration.RegistrationSubmissionService;
import com.volunteerflow.registration.RegistrationSubmissionService.AnswerInput;
import com.volunteerflow.registration.RegistrationSubmissionService.RegistrationResult;
import com.volunteerflow.registration.RegistrationSubmissionService.SubmitRegistrationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Explicit live-MySQL Stage 3 release gate ({@code -Dtest=Stage3VmAcceptanceIT}).
 *
 * <p>The fixture is committed because concurrency workers use separate connections. Every generated
 * organization and user ID is recorded before dependent writes, and {@code finally} removes only
 * those exact targets child-first. The scheduled scanners are replaced so this test can never
 * process another user's due activity or promotion offer.
 */
@SpringBootTest(properties = "volunteerflow.bootstrap-admin.username=")
class Stage3VmAcceptanceIT {
  private static final Set<String> ACCEPTED_DUE_INDEXES =
      Set.of("idx_activity_status_end_id");

  private final AppUserMapper users;
  private final OrganizationService organizations;
  private final ActivityService activities;
  private final ActivityChangeService activityChanges;
  private final ActivityQuestionService questions;
  private final RegistrationSubmissionService submissions;
  private final RegistrationCancellationService cancellations;
  private final RegistrationReviewService reviews;
  private final PromotionService promotions;
  private final PromotionExpiryService promotionExpiry;
  private final PromotionOfferMapper offers;
  private final RegistrationCycleMapper cycles;
  private final CheckinSessionService sessions;
  private final CheckinService checkins;
  private final AttendanceQueryService attendance;
  private final ActivityFinalizationService finalizer;
  private final ActivityMapper activityMapper;
  private final CheckinCodeGenerator codes;
  private final NotificationService notificationQueries;
  private final JdbcTemplate sql;
  private final Clock clock;

  @MockitoBean private ActivityFinalizationJob automaticActivityFinalizer;
  @MockitoBean private PromotionExpiryJob automaticPromotionExpiry;

  private final String prefix = "s3it-" + UUID.randomUUID().toString().replace("-", "");
  private final List<AppUser> createdUsers = new ArrayList<>();
  private final List<Long> organizationIds = new ArrayList<>();
  private final List<Long> memberIds = new ArrayList<>();
  private Long ownerId;
  private Long organizationId;
  private boolean workersStopped = true;

  @Autowired
  Stage3VmAcceptanceIT(
      AppUserMapper users,
      OrganizationService organizations,
      ActivityService activities,
      ActivityChangeService activityChanges,
      ActivityQuestionService questions,
      RegistrationSubmissionService submissions,
      RegistrationCancellationService cancellations,
      RegistrationReviewService reviews,
      PromotionService promotions,
      PromotionExpiryService promotionExpiry,
      PromotionOfferMapper offers,
      RegistrationCycleMapper cycles,
      CheckinSessionService sessions,
      CheckinService checkins,
      AttendanceQueryService attendance,
      ActivityFinalizationService finalizer,
      ActivityMapper activityMapper,
      CheckinCodeGenerator codes,
      NotificationService notificationQueries,
      JdbcTemplate sql,
      Clock clock) {
    this.users = users;
    this.organizations = organizations;
    this.activities = activities;
    this.activityChanges = activityChanges;
    this.questions = questions;
    this.submissions = submissions;
    this.cancellations = cancellations;
    this.reviews = reviews;
    this.promotions = promotions;
    this.promotionExpiry = promotionExpiry;
    this.offers = offers;
    this.cycles = cycles;
    this.sessions = sessions;
    this.checkins = checkins;
    this.attendance = attendance;
    this.finalizer = finalizer;
    this.activityMapper = activityMapper;
    this.codes = codes;
    this.notificationQueries = notificationQueries;
    this.sql = sql;
    this.clock = clock;
  }

  @Test
  void completesNotificationCheckinAndAttendanceLoopAgainstConfiguredMysql() throws Exception {
    try {
      assertSchemaReadyWithoutWriting();
      createOrganizationAndMembers(7);

      ActivityFixture main = createActivity("FIRST_COME", 4, "main");
      List<RegistrationResult> results =
          memberIds.stream().map(memberId -> submit(memberId, main)).toList();
      assertThat(results).extracting(RegistrationResult::status)
          .containsExactly(
              "CONFIRMED", "CONFIRMED", "CONFIRMED", "CONFIRMED",
              "WAITLISTED", "WAITLISTED", "WAITLISTED");
      assertNotificationRecipients("REGISTRATION_RESULT", memberIds);

      cancellations.cancel(
          memberIds.get(4),
          results.get(4).registrationId(),
          new RegistrationCancellationService.CancellationRequest("不再参加"));
      Activity current = activityMapper.selectById(main.activityId());
      var change =
          activityChanges.changeActivity(
              ownerId,
              main.activityId(),
              new ActivityChangeService.ActivityChangeRequest(
                  prefix + "-changed",
                  current.getDescription(),
                  current.getLocation(),
                  current.getRegistrationEndAt(),
                  current.getFreeCancelDeadlineAt(),
                  current.getActivityStartAt(),
                  current.getActivityEndAt(),
                  "集合地点说明已更新"));
      List<Long> changeRecipients =
          List.of(
              memberIds.get(0),
              memberIds.get(1),
              memberIds.get(2),
              memberIds.get(3),
              memberIds.get(5),
              memberIds.get(6));
      assertThat(change.notifiedRecipients()).isEqualTo(changeRecipients.size());
      assertNotificationRecipients("ACTIVITY_CHANGE", changeRecipients);
      assertThat(notificationCount("ACTIVITY_CHANGE", memberIds.get(4))).isZero();

      cancellations.cancel(
          memberIds.get(0),
          results.get(0).registrationId(),
          new RegistrationCancellationService.CancellationRequest("释放名额"));
      PromotionOffer expiring = pendingOffer(main.positionId());
      assertThat(cycles.selectById(expiring.getRegistrationCycleId()).getUserId())
          .isEqualTo(memberIds.get(5));
      LocalDateTime databaseNow = offers.currentDatabaseTime();
      assertThat(
              sql.update(
                  "UPDATE promotion_offer SET expires_at=? WHERE id=? AND organization_id=?",
                  databaseNow.minusSeconds(1),
                  expiring.getId(),
                  organizationId))
          .isEqualTo(1);
      assertThat(promotionExpiry.expireOne(expiring.getId())).isTrue();
      PromotionOffer accepted = pendingOffer(main.positionId());
      assertThat(cycles.selectById(accepted.getRegistrationCycleId()).getUserId())
          .isEqualTo(memberIds.get(6));
      promotions.respond(
          memberIds.get(6),
          accepted.getId(),
          new PromotionService.PromotionResponseRequest("ACCEPT"));
      assertThat(notificationCount("PROMOTION_OFFER", memberIds.get(5))).isEqualTo(2);
      assertThat(notificationCount("PROMOTION_OFFER", memberIds.get(6))).isEqualTo(1);

      verifyReviewNotification();

      var opened =
          sessions.open(
              ownerId,
              main.activityId(),
              new CheckinSessionService.OpenSessionRequest(15));
      assertDigestOnlyPersistence(opened.sessionId(), opened.code());
      List<Long> confirmedMembers =
          List.of(memberIds.get(1), memberIds.get(2), memberIds.get(3), memberIds.get(6));
      assertNotificationRecipientsForBusiness(
          "CHECKIN_OPENED", main.activityId(), confirmedMembers);
      assertThat(sessions.currentForMember(memberIds.get(1), main.activityId()).sessionId())
          .isEqualTo(opened.sessionId());
      assertThat(sessions.currentForManager(ownerId, main.activityId()).sessionId())
          .isEqualTo(opened.sessionId());
      verifyNotificationQueries(memberIds.get(1));

      var firstSelfRequest =
          attendance.selfRequest(memberIds.get(1), opened.sessionId(), opened.code());
      assertThat(checkins.selfCheckin(memberIds.get(1), opened.sessionId(), firstSelfRequest).method())
          .isEqualTo("SELF_SERVICE");

      List<String> duplicateRace =
          concurrently(
              List.of(
                  () -> selfOutcome(memberIds.get(2), opened.sessionId(), opened.code()),
                  () -> selfOutcome(memberIds.get(2), opened.sessionId(), opened.code())));
      assertThat(duplicateRace).containsExactlyInAnyOrder("SELF_SERVICE", "ALREADY_CHECKED_IN");

      var manualRequest =
          attendance.manualRequest(
              ownerId,
              opened.sessionId(),
              results.get(3).registrationId(),
              " 现场证件核验 ");
      assertThat(checkins.manualCheckin(ownerId, opened.sessionId(), manualRequest).method())
          .isEqualTo("MANUAL");
      assertThat(
              sql.queryForObject(
                  "SELECT manual_reason FROM checkin_record"
                      + " WHERE organization_id=? AND activity_id=? AND user_id=?",
                  String.class,
                  organizationId,
                  main.activityId(),
                  memberIds.get(3)))
          .isEqualTo("现场证件核验");
      assertThat(attendance.current(memberIds.get(1), main.activityId()).attendance().attendanceStatus())
          .isEqualTo("CHECKED_IN");
      assertThat(sessions.close(ownerId, opened.sessionId()).status()).isEqualTo("CLOSED");
      assertThat(sessions.currentForManager(ownerId, main.activityId())).isNull();

      AttendanceSummary liveSummary = attendance.summary(ownerId, main.activityId());
      assertSummary(liveSummary, "PUBLISHED", false, 4, 3, 0, 1);

      LocalDateTime dueEnd = LocalDateTime.of(2001, 2, 3, 4, 5, 6, 123_456_000);
      assertThat(
              sql.update(
                  "UPDATE activity SET registration_start_at=?,registration_end_at=?,"
                      + " activity_start_at=?,activity_end_at=?"
                      + " WHERE id=? AND organization_id=? AND status='PUBLISHED'",
                  dueEnd.minusDays(3),
                  dueEnd.minusDays(2),
                  dueEnd.minusDays(1),
                  dueEnd,
                  main.activityId(),
                  organizationId))
          .isEqualTo(1);
      LocalDateTime finalizationNow = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
      assertIndexedBoundedDueQuery(main.activityId(), dueEnd, finalizationNow);
      assertThat(finalizer.finalizeOne(main.activityId(), finalizationNow)).isTrue();
      assertThat(finalizer.finalizeOne(main.activityId(), finalizationNow)).isFalse();

      AttendanceSummary finalSummary = attendance.summary(ownerId, main.activityId());
      assertSummary(finalSummary, "FINISHED", true, 4, 3, 1, 0);
      assertThat(finalSummary.correctionSession().sessionId()).isEqualTo(opened.sessionId());

      var correction =
          attendance.manualRequest(
              ownerId,
              finalSummary.correctionSession().sessionId(),
              results.get(6).registrationId(),
              "活动结束后核对纸质签到表");
      assertThat(checkins.manualCheckin(ownerId, opened.sessionId(), correction).method())
          .isEqualTo("MANUAL");
      AttendanceSummary correctedSummary = attendance.summary(ownerId, main.activityId());
      assertSummary(correctedSummary, "FINISHED", true, 4, 4, 0, 0);

      assertThat(
              count(
                  "SELECT COUNT(*) FROM checkin_record"
                      + " WHERE organization_id=? AND activity_id=?",
                  organizationId,
                  main.activityId()))
          .isEqualTo(4);
      assertThat(auditCount("checkin.self.created")).isEqualTo(2);
      assertThat(auditCount("checkin.manual.created")).isEqualTo(2);
      assertThat(auditCount("activity.finished")).isEqualTo(1);
      assertThat(
              count(
                  "SELECT COUNT(*) FROM notification WHERE organization_id=?"
                      + " AND (content LIKE ? OR title LIKE ?)",
                  organizationId,
                  "%" + opened.code() + "%",
                  "%" + opened.code() + "%"))
          .as("plaintext check-in code must not appear in notifications")
          .isZero();
      assertThat(
              count(
                  "SELECT COUNT(*) FROM audit_log WHERE organization_id=?"
                      + " AND (before_json LIKE ? OR after_json LIKE ? OR reason LIKE ?)",
                  organizationId,
                  "%" + opened.code() + "%",
                  "%" + opened.code() + "%",
                  "%" + opened.code() + "%"))
          .as("plaintext check-in code must not appear in audit data")
          .isZero();

      System.out.println(
          "Stage3 live assertions passed: registration/review/promotion/change/check-in"
              + " notifications, digest-only session, duplicate race, two manual check-ins,"
              + " indexed bounded finalization, NO_SHOW correction, and final 4/4 attendance.");
    } finally {
      if (workersStopped) {
        cleanup();
      } else {
        throw new IllegalStateException("Workers still active; cleanup withheld for run " + prefix);
      }
    }
  }

  private void assertSchemaReadyWithoutWriting() {
    assertThat(sql.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("volunteer_flow");
    assertThat(
            count(
                "SELECT COUNT(*) FROM information_schema.columns"
                    + " WHERE table_schema=DATABASE() AND table_name='registration_cycle'"
                    + " AND column_name='attendance_status'"))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM information_schema.statistics"
                    + " WHERE table_schema=DATABASE() AND table_name='activity'"
                    + " AND index_name='idx_activity_status_end_id'"))
        .isEqualTo(3);
    System.out.println("Stage3 read-only preflight passed: database and V9 attendance index exist.");
  }

  private void verifyReviewNotification() {
    ActivityFixture review = createActivity("REVIEW", 1, "review");
    RegistrationResult submitted = submit(memberIds.get(0), review);
    assertThat(submitted.status()).isEqualTo("PENDING_REVIEW");
    reviews.decide(
        ownerId,
        submitted.registrationId(),
        new RegistrationReviewService.ReviewDecisionRequest("REJECT", "材料不符合本次岗位要求"));
    assertThat(notificationCount("REGISTRATION_REVIEW", memberIds.get(0))).isEqualTo(1);
  }

  private void verifyNotificationQueries(Long userId) {
    long before = notificationQueries.unreadCount(userId);
    var page = notificationQueries.page(userId, null, 20);
    assertThat(before).isPositive();
    assertThat(page.items()).isNotEmpty();
    notificationQueries.markRead(userId, page.items().get(0).id());
    assertThat(notificationQueries.unreadCount(userId)).isEqualTo(before - 1);
    assertThat(notificationQueries.markAllRead(userId)).isEqualTo(before - 1);
    assertThat(notificationQueries.unreadCount(userId)).isZero();
  }

  private void assertDigestOnlyPersistence(Long sessionId, String plaintextCode) {
    Map<String, Object> row =
        sql.queryForMap(
            "SELECT static_code_hash,status FROM checkin_session"
                + " WHERE id=? AND organization_id=?",
            sessionId,
            organizationId);
    String storedDigest = (String) row.get("static_code_hash");
    assertThat(storedDigest).matches("^[0-9a-f]{64}$");
    assertThat(storedDigest)
        .isEqualTo(HexFormat.of().formatHex(codes.digest(codes.normalize(plaintextCode))));
    assertThat(storedDigest).isNotEqualToIgnoringCase(plaintextCode);
    assertThat(row.get("status")).isEqualTo("OPEN");
  }

  private void assertIndexedBoundedDueQuery(
      Long activityId, LocalDateTime dueEnd, LocalDateTime now) {
    List<ActivityMapper.DueActivityId> page =
        activityMapper.selectDueIds(now, dueEnd, activityId - 1, 1);
    assertThat(page).singleElement().satisfies(row -> assertThat(row.id()).isEqualTo(activityId));

    Map<String, Object> explain =
        sql.queryForMap(
            "EXPLAIN FORMAT=TRADITIONAL SELECT id,activity_end_at FROM activity"
                + " FORCE INDEX (idx_activity_status_end_id)"
                + " WHERE status='PUBLISHED' AND activity_end_at<=?"
                + " AND (activity_end_at>? OR (activity_end_at=? AND id>?))"
                + " ORDER BY activity_end_at,id LIMIT ?",
            now,
            dueEnd,
            dueEnd,
            activityId - 1,
            1);
    String accessType = String.valueOf(valueIgnoringCase(explain, "type"));
    String chosenKey = String.valueOf(valueIgnoringCase(explain, "key"));
    System.out.println("Stage3 EXPLAIN raw plan: " + explain);
    assertThat(accessType).isNotEqualToIgnoringCase("ALL");
    assertThat(ACCEPTED_DUE_INDEXES).contains(chosenKey);
    System.out.println(
        "Stage3 EXPLAIN passed: type=" + accessType + ", key=" + chosenKey + ", limit=1.");
  }

  private Object valueIgnoringCase(Map<String, Object> row, String name) {
    return row.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private void assertSummary(
      AttendanceSummary summary,
      String activityStatus,
      boolean finalStatistics,
      long confirmed,
      long checkedIn,
      long noShow,
      long pending) {
    assertThat(summary.activityStatus()).isEqualTo(activityStatus);
    assertThat(summary.finalStatistics()).isEqualTo(finalStatistics);
    assertThat(summary.confirmedCount()).isEqualTo(confirmed);
    assertThat(summary.checkedInCount()).isEqualTo(checkedIn);
    assertThat(summary.noShowCount()).isEqualTo(noShow);
    assertThat(summary.pendingCount()).isEqualTo(pending);
    assertThat(summary.positions()).singleElement().satisfies(position -> {
      assertThat(position.confirmedCount()).isEqualTo(confirmed);
      assertThat(position.checkedInCount()).isEqualTo(checkedIn);
      assertThat(position.noShowCount()).isEqualTo(noShow);
      assertThat(position.pendingCount()).isEqualTo(pending);
    });
  }

  private String selfOutcome(Long userId, Long sessionId, String code) {
    try {
      return checkins
          .selfCheckin(userId, sessionId, attendance.selfRequest(userId, sessionId, code))
          .method();
    } catch (BusinessException exception) {
      return exception.code();
    }
  }

  private void createOrganizationAndMembers(int memberCount) {
    System.out.println("Stage3 fixture starting: run=" + prefix + ", members=" + memberCount);
    AppUser platformAdmin = user("admin", "PLATFORM_ADMIN");
    ownerId = user("owner", "USER").getId();
    var organization =
        organizations.create(
            platformAdmin.getId(),
            "PLATFORM_ADMIN",
            new OrganizationService.CreateOrganizationRequest(
                prefix, "isolated Stage 3 acceptance", ownerId));
    organizationId = organization.getId();
    organizationIds.add(organizationId);
    System.out.println("Stage3 fixture organization: run=" + prefix + ", id=" + organizationId);
    var invitation =
        organizations.createInvite(
            ownerId,
            organizationId,
            new CreateInviteRequest(
                memberCount, Instant.now(clock).plus(1, ChronoUnit.DAYS)));
    for (int index = 0; index < memberCount; index++) {
      AppUser member = user("m" + index, "USER");
      organizations.joinByInvite(member.getId(), invitation.code());
      memberIds.add(member.getId());
    }
  }

  private ActivityFixture createActivity(String mode, int capacity, String suffix) {
    LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    Activity activity =
        activities.create(
            ownerId,
            organizationId,
            new ActivityService.ActivityRequest(
                prefix + "-" + suffix,
                "Stage 3 live acceptance",
                "test-only",
                now.minusDays(1),
                now.plusDays(1),
                now.plusDays(2),
                now.plusDays(3),
                now.plusDays(3).plusHours(2)));
    var position =
        activities.addPosition(
            ownerId,
            activity.getId(),
            new ActivityService.PositionRequest(
                suffix + "-position",
                "acceptance",
                capacity,
                mode,
                30,
                null,
                null,
                null));
    var question =
        questions.createActivityQuestion(
            ownerId,
            activity.getId(),
            new ActivityQuestionService.QuestionRequest(
                "TEXT", "培训意愿", true, List.of(), 1));
    activities.publish(ownerId, activity.getId());
    return new ActivityFixture(activity.getId(), position.getId(), question.getId());
  }

  private RegistrationResult submit(Long userId, ActivityFixture fixture) {
    return submissions.submit(
        userId,
        fixture.activityId(),
        new SubmitRegistrationRequest(
            fixture.positionId(),
            List.of(
                new AnswerInput(
                    "ACTIVITY", fixture.questionId(), TextNode.valueOf("接受培训")))));
  }

  private AppUser user(String suffix, String platformRole) {
    AppUser user = new AppUser();
    user.setId(IdWorker.getId());
    user.setUsername(prefix + "-" + suffix);
    user.setStudentNumber(prefix + "-" + suffix);
    user.setPasswordHash("acceptance-not-a-login-password");
    user.setRealName("Acceptance " + suffix);
    user.setContact("acceptance-only");
    user.setStatus("ACTIVE");
    user.setPlatformRole(platformRole);
    user.setVersion(0);
    createdUsers.add(user);
    System.out.println(
        "Stage3 fixture user: run=" + prefix + ", id=" + user.getId() + ", username="
            + user.getUsername());
    users.insert(user);
    return user;
  }

  private PromotionOffer pendingOffer(Long positionId) {
    Long id =
        sql.queryForObject(
            "SELECT id FROM promotion_offer"
                + " WHERE organization_id=? AND position_id=? AND status='PENDING'",
            Long.class,
            organizationId,
            positionId);
    return offers.selectById(id);
  }

  private void assertNotificationRecipients(String type, List<Long> expectedUserIds) {
    assertThat(
            sql.queryForList(
                "SELECT user_id FROM notification"
                    + " WHERE organization_id=? AND notification_type=? ORDER BY user_id,id",
                Long.class,
                organizationId,
                type))
        .containsExactlyInAnyOrderElementsOf(expectedUserIds);
  }

  private void assertNotificationRecipientsForBusiness(
      String type, Long businessId, List<Long> expectedUserIds) {
    assertThat(
            sql.queryForList(
                "SELECT user_id FROM notification"
                    + " WHERE organization_id=? AND notification_type=? AND business_id=?"
                    + " ORDER BY user_id,id",
                Long.class,
                organizationId,
                type,
                businessId))
        .containsExactlyInAnyOrderElementsOf(expectedUserIds);
  }

  private long notificationCount(String type, Long userId) {
    return count(
        "SELECT COUNT(*) FROM notification"
            + " WHERE organization_id=? AND notification_type=? AND user_id=?",
        organizationId,
        type,
        userId);
  }

  private long auditCount(String action) {
    return count(
        "SELECT COUNT(*) FROM audit_log WHERE organization_id=? AND action=?",
        organizationId,
        action);
  }

  private long count(String statement, Object... arguments) {
    return sql.queryForObject(statement, Long.class, arguments);
  }

  private <T> List<T> concurrently(List<Callable<T>> work) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(work.size());
    workersStopped = false;
    CountDownLatch ready = new CountDownLatch(work.size());
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    try {
      for (Callable<T> task : work) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Start latch timed out");
                  }
                  return task.call();
                }));
      }
      assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      start.countDown();
      executor.shutdownNow();
      workersStopped = executor.awaitTermination(30, TimeUnit.SECONDS);
      if (!workersStopped) {
        throw new IllegalStateException("Worker shutdown timed out for run " + prefix);
      }
    }
  }

  private void cleanup() {
    for (Long id : organizationIds) {
      assertThat(sql.queryForObject("SELECT name FROM organization WHERE id=?", String.class, id))
          .isEqualTo(prefix);
    }
    for (AppUser user : createdUsers) {
      assertThat(user.getUsername()).startsWith(prefix + "-");
      assertThat(
              sql.queryForList(
                  "SELECT username FROM app_user WHERE id=?", String.class, user.getId()))
          .allMatch(user.getUsername()::equals);
    }
    for (Long id : organizationIds) {
      for (String table :
          List.of(
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
              "audit_log")) {
        sql.update("DELETE FROM " + table + " WHERE organization_id=?", id);
        assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE organization_id=?", id))
            .as(table + " cleanup")
            .isZero();
      }
      assertThat(sql.update("DELETE FROM organization WHERE id=? AND name=?", id, prefix))
          .isEqualTo(1);
      assertThat(count("SELECT COUNT(*) FROM organization WHERE id=?", id)).isZero();
    }
    for (AppUser user : createdUsers) {
      sql.update(
          "DELETE FROM app_user WHERE id=? AND username=?", user.getId(), user.getUsername());
      assertThat(count("SELECT COUNT(*) FROM app_user WHERE id=?", user.getId())).isZero();
    }
    long generatedOrganizations =
        organizationIds.stream()
            .mapToLong(id -> count("SELECT COUNT(*) FROM organization WHERE id=?", id))
            .sum();
    long generatedUsers =
        createdUsers.stream()
            .mapToLong(user -> count("SELECT COUNT(*) FROM app_user WHERE id=?", user.getId()))
            .sum();
    assertThat(generatedOrganizations).isZero();
    assertThat(generatedUsers).isZero();
    System.out.println(
        "Stage3 cleanup verified for run "
            + prefix
            + ": 0 generated organizations, 0 generated users remain ("
            + organizationIds.size()
            + " organization and "
            + createdUsers.size()
            + " exact user IDs removed)." );
  }

  private record ActivityFixture(Long activityId, Long positionId, Long questionId) {}
}
