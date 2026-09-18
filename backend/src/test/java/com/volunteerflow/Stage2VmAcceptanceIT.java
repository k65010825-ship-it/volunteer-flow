package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.node.TextNode;
import com.volunteerflow.activity.ActivityQuestionService;
import com.volunteerflow.activity.ActivityService;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.auth.AppUser;
import com.volunteerflow.auth.AppUserMapper;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.organization.CreateInviteRequest;
import com.volunteerflow.organization.OrganizationService;
import com.volunteerflow.registration.PromotionExpiryJob;
import com.volunteerflow.registration.PromotionExpiryService;
import com.volunteerflow.registration.PromotionOffer;
import com.volunteerflow.registration.PromotionOfferMapper;
import com.volunteerflow.registration.PromotionService;
import com.volunteerflow.registration.RegistrationCancellationService;
import com.volunteerflow.registration.RegistrationCycleMapper;
import com.volunteerflow.registration.RegistrationQueryService;
import com.volunteerflow.registration.RegistrationReviewService;
import com.volunteerflow.registration.RegistrationSubmissionService;
import com.volunteerflow.registration.RegistrationSubmissionService.AnswerInput;
import com.volunteerflow.registration.RegistrationSubmissionService.RegistrationResult;
import com.volunteerflow.registration.RegistrationSubmissionService.SubmitRegistrationRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Opt-in live MySQL acceptance: -Dtest=Stage2VmAcceptanceIT. No replacement database or skips. All
 * fixture writes are committed because worker transactions use separate connections. Exact
 * generated IDs are validated and removed child-first in finally.
 */
@SpringBootTest(properties = "volunteerflow.bootstrap-admin.username=")
class Stage2VmAcceptanceIT {
  private final AppUserMapper users;
  private final OrganizationService organizations;
  private final ActivityService activities;
  private final ActivityQuestionService questions;
  private final RegistrationSubmissionService submissions;
  private final RegistrationCancellationService cancellations;
  private final RegistrationReviewService reviews;
  private final RegistrationQueryService queries;
  private final PromotionService promotions;
  private final PromotionExpiryService expiry;
  private final PromotionOfferMapper offers;
  private final RegistrationCycleMapper cycles;
  private final JdbcTemplate sql;
  private final Clock clock;

  // Never let the global scanner process unrelated users' expired offers during a test.
  // The real expiry service below is invoked only for this run's generated offer IDs.
  @MockitoBean
  private PromotionExpiryJob automaticScanner;

  @MockitoSpyBean
  private AuditService audit;

  private final String prefix =
      "s2it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  private final List<AppUser> createdUsers = new ArrayList<>();
  private final List<Long> organizationIds = new ArrayList<>();
  private final List<Long> memberIds = new ArrayList<>();
  private Long ownerId;
  private Long outsiderId;
  private Long organizationId;
  private boolean workersStopped = true;

  @Autowired
  Stage2VmAcceptanceIT(
      AppUserMapper users,
      OrganizationService organizations,
      ActivityService activities,
      ActivityQuestionService questions,
      RegistrationSubmissionService submissions,
      RegistrationCancellationService cancellations,
      RegistrationReviewService reviews,
      RegistrationQueryService queries,
      PromotionService promotions,
      PromotionExpiryService expiry,
      PromotionOfferMapper offers,
      RegistrationCycleMapper cycles,
      JdbcTemplate sql,
      Clock clock) {
    this.users = users;
    this.organizations = organizations;
    this.activities = activities;
    this.questions = questions;
    this.submissions = submissions;
    this.cancellations = cancellations;
    this.reviews = reviews;
    this.queries = queries;
    this.promotions = promotions;
    this.expiry = expiry;
    this.offers = offers;
    this.cycles = cycles;
    this.sql = sql;
    this.clock = clock;
  }

  @Test
  void oneHundredConcurrentUsersNeverOversellTwentyPlaces() throws Exception {
    try {
      createOrganizationAndMembers();
      Fixture fixture = createActivity("FIRST_COME", 20);
      List<Callable<RegistrationResult>> work =
          memberIds.stream()
              .limit(100)
              .<Callable<RegistrationResult>>map(userId -> () -> submit(userId, fixture))
              .toList();
      List<RegistrationResult> results = concurrently(work);
      assertThat(results).filteredOn(result -> result.status().equals("CONFIRMED")).hasSize(20);
      assertThat(results).filteredOn(result -> result.status().equals("WAITLISTED")).hasSize(80);
      assertThat(cycles.countConfirmed(fixture.positionId())).isEqualTo(20);
      assertThat(offers.countActiveReservations(fixture.positionId())).isZero();
      assertThat(
              count(
                  "SELECT COUNT(*) FROM registration WHERE activity_id = ?", fixture.activityId()))
          .isEqualTo(100);
      assertThat(
              count(
                  """
SELECT COUNT(*) FROM (
  SELECT user_id FROM registration_cycle
  WHERE activity_id = ? AND status IN ('CONFIRMED', 'WAITLISTED', 'PENDING_REVIEW')
  GROUP BY user_id HAVING COUNT(*) > 1
) duplicates
""",
                  fixture.activityId()))
          .isZero();
      List<Long> sequences =
          sql.queryForList(
              "SELECT waitlist_sequence FROM registration_cycle WHERE position_id = ? AND status ="
                  + " 'WAITLISTED' ORDER BY waitlist_sequence",
              Long.class,
              fixture.positionId());
      assertThat(sequences)
          .containsExactlyElementsOf(LongStream.rangeClosed(1, 80).boxed().toList());
      assertThat(
              count(
                  "SELECT next_waitlist_sequence FROM activity_position WHERE id = ?",
                  fixture.positionId()))
          .isEqualTo(81);
      assertThat(
              count(
                  "SELECT COUNT(*) FROM registration_answer WHERE organization_id = ?",
                  organizationId))
          .isEqualTo(100);
      assertThat(auditCount("registration.submitted")).isEqualTo(100);
      verifyProjectionAndOwnership(fixture);
      verifyCancellationAndAcceptance(fixture, results);
      verifyExpiryContention(fixture, results);
      verifySameMemberAcrossPositions(fixture);
      verifyReviewAndRollback();
      System.out.println(
          "Stage2 live assertions passed: 100 submissions, 20 confirmed, 80 waitlisted;"
              + " cancellation, acceptance, expiry contention, review, privacy, rollback.");
    } finally {
      reset(audit);
      if (workersStopped) cleanup();
      else
        throw new IllegalStateException("Workers still active; cleanup withheld for run " + prefix);
    }
  }

  private void verifyProjectionAndOwnership(Fixture fixture) {
    var first = queries.listForPosition(ownerId, fixture.positionId(), "WAITLISTED", 1, 20);
    var second = queries.listForPosition(ownerId, fixture.positionId(), "WAITLISTED", 2, 20);
    assertThat(first.total()).isEqualTo(80);
    assertThat(first.items()).hasSize(20);
    assertThat(second.items()).hasSize(20);
    assertThat(first.items())
        .extracting(item -> item.cycleId())
        .doesNotContainAnyElementsOf(second.items().stream().map(item -> item.cycleId()).toList());
    assertThat(first.items())
        .allSatisfy(
            item -> {
              assertThat(item.answers()).hasSize(1);
              assertThat(item.answers().get(0).answer().asText()).isEqualTo("接受培训");
            });
    var candidate = first.items().get(0);
    assertThat(queries.getOwn(candidate.userId(), candidate.registrationId()).status())
        .isEqualTo("WAITLISTED");
    assertThat(queries.listOwn(candidate.userId())).hasSize(1);
    assertThatThrownBy(() -> queries.getOwn(outsiderId, candidate.registrationId()))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("REGISTRATION_NOT_FOUND");
    assertThatThrownBy(() -> queries.listForPosition(outsiderId, fixture.positionId(), null, 1, 20))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(
            () -> queries.listForPosition(memberIds.get(0), fixture.positionId(), null, 1, 20))
        .isInstanceOf(BusinessException.class);
  }

  private void verifyCancellationAndAcceptance(Fixture fixture, List<RegistrationResult> results)
      throws Exception {
    RegistrationResult confirmed =
        results.stream()
            .filter(result -> result.status().equals("CONFIRMED"))
            .findFirst()
            .orElseThrow();
    Long userId = cycles.selectById(confirmed.cycleId()).getUserId();
    var outcomes =
        concurrently(
            List.of(
                () -> cancelOutcome(userId, confirmed.registrationId()),
                () -> cancelOutcome(userId, confirmed.registrationId())));
    assertThat(outcomes).containsExactlyInAnyOrder("CANCELED", "CONFLICT");
    assertThat(pendingOffers(fixture.positionId())).isEqualTo(1);
    PromotionOffer invitation = pendingOffer(fixture.positionId());
    var candidate = cycles.selectById(invitation.getRegistrationCycleId());
    assertThat(candidate.getWaitlistSequence()).isEqualTo(1L);
    assertThat(cycles.countConfirmed(fixture.positionId())).isEqualTo(19);
    assertThat(offers.countActiveReservations(fixture.positionId())).isEqualTo(1);
    var responses =
        concurrently(
            List.of(
                () -> responseOutcome(candidate.getUserId(), invitation.getId()),
                () -> responseOutcome(candidate.getUserId(), invitation.getId())));
    assertThat(responses).containsExactlyInAnyOrder("ACCEPTED", "CONFLICT");
    assertThat(cycles.selectById(candidate.getId()).getStatus()).isEqualTo("CONFIRMED");
    assertThat(cycles.countConfirmed(fixture.positionId())).isEqualTo(20);
    assertThat(offers.countActiveReservations(fixture.positionId())).isZero();
    assertThat(auditCount("registration.canceled")).isEqualTo(1);
    assertThat(auditCount("promotion.offered")).isEqualTo(1);
    assertThat(auditCount("promotion.accepted")).isEqualTo(1);
  }

  private void verifyExpiryContention(Fixture fixture, List<RegistrationResult> results)
      throws Exception {
    RegistrationResult confirmed =
        results.stream()
            .filter(result -> cycles.selectById(result.cycleId()).getStatus().equals("CONFIRMED"))
            .findFirst()
            .orElseThrow();
    cancellations.cancel(
        cycles.selectById(confirmed.cycleId()).getUserId(),
        confirmed.registrationId(),
        new RegistrationCancellationService.CancellationRequest(null));
    PromotionOffer invitation = pendingOffer(fixture.positionId());
    assertThat(cycles.selectById(invitation.getRegistrationCycleId()).getWaitlistSequence())
        .isEqualTo(2L);
    LocalDateTime isolatedExpiry = LocalDateTime.of(2000, 1, 1, 0, 0);
    assertThat(
            count(
                "SELECT COUNT(*) FROM promotion_offer WHERE id <> ? AND status = 'PENDING'"
                    + " AND expires_at <= ?",
                invitation.getId(),
                isolatedExpiry))
        .as("the bounded scan fixture must sort before every unrelated pending offer")
        .isZero();
    assertThat(
            sql.update(
                "UPDATE promotion_offer SET expires_at = ? WHERE id = ? AND organization_id = ?",
                isolatedExpiry,
                invitation.getId(),
                organizationId))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT COUNT(*) FROM promotion_offer WHERE status = 'PENDING' AND expires_at <= ?",
                isolatedExpiry))
        .isEqualTo(1);
    LocalDateTime scanTime = offers.currentDatabaseTime();
    List<Long> scannedIds = offers.selectExpiredIds(scanTime, 1);
    assertThat(scannedIds).containsExactly(invitation.getId());
    for (Long scannedId : scannedIds) {
      PromotionOffer scanned = offers.selectById(scannedId);
      assertThat(scanned.getStatus()).isEqualTo("PENDING");
      assertThat(scanned.getExpiresAt()).isBeforeOrEqualTo(scanTime);
    }
    List<Boolean> outcomes =
        concurrently(
            List.of(
                () -> expiry.expireOne(invitation.getId()),
                () -> expiry.expireOne(invitation.getId())));
    assertThat(outcomes).containsExactlyInAnyOrder(true, false);
    assertThat(offers.selectById(invitation.getId()).getStatus()).isEqualTo("EXPIRED");
    assertThat(cycles.selectById(invitation.getRegistrationCycleId()).getStatus())
        .isEqualTo("PROMOTION_EXPIRED");
    PromotionOffer successor = pendingOffer(fixture.positionId());
    assertThat(cycles.selectById(successor.getRegistrationCycleId()).getWaitlistSequence())
        .isEqualTo(3L);
    assertThat(pendingOffers(fixture.positionId())).isEqualTo(1);
    assertThat(
            cycles.countConfirmed(fixture.positionId())
                + offers.countActiveReservations(fixture.positionId()))
        .isEqualTo(20);
    assertThat(auditCount("promotion.expired")).isEqualTo(1);
  }

  private void verifySameMemberAcrossPositions(Fixture fixture) throws Exception {
    Long userId = memberIds.get(100);
    List<String> outcomes =
        concurrently(
            List.of(
                () -> submissionOutcome(userId, fixture),
                () ->
                    submissionOutcome(
                        userId,
                        new Fixture(
                            fixture.activityId(),
                            fixture.secondPositionId(),
                            fixture.secondPositionId(),
                            fixture.questionId()))));
    assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS", "REGISTRATION_ALREADY_ACTIVE");
    assertThat(
            count(
                "SELECT COUNT(*) FROM registration_cycle WHERE activity_id = ? AND user_id = ? AND"
                    + " status IN ('CONFIRMED', 'WAITLISTED', 'PENDING_REVIEW')",
                fixture.activityId(),
                userId))
        .isEqualTo(1);
  }

  private void verifyReviewAndRollback() {
    Fixture fixture = createActivity("REVIEW", 1);
    var first = submit(memberIds.get(0), fixture);
    var second = submit(memberIds.get(1), fixture);
    assertThat(first.status()).isEqualTo("PENDING_REVIEW");
    // Inject a genuine database NOT NULL violation at audit persistence, after cycle mutation.
    doAnswer(
            invocation -> {
              sql.update(
                  "INSERT INTO audit_log (id, organization_id, action, resource_type) VALUES (?, ?,"
                      + " NULL, 'acceptance')",
                  IdWorker.getId(),
                  organizationId);
              return null;
            })
        .when(audit)
        .record(eq(organizationId), eq(ownerId), eq("registration.reviewed"), any(), any(), any());
    assertThatThrownBy(
            () ->
                reviews.decide(
                    ownerId,
                    first.registrationId(),
                    new RegistrationReviewService.ReviewDecisionRequest("CONFIRM", "符合要求")))
        .isInstanceOf(DataIntegrityViolationException.class);
    reset(audit);
    assertThat(cycles.selectById(first.cycleId()).getStatus()).isEqualTo("PENDING_REVIEW");
    assertThat(cycles.countConfirmed(fixture.positionId())).isZero();
    assertThat(auditCount("registration.reviewed")).isZero();
    reviews.decide(
        ownerId,
        first.registrationId(),
        new RegistrationReviewService.ReviewDecisionRequest("CONFIRM", " 符合要求 "));
    assertThat(cycles.selectById(first.cycleId()).getReviewReason()).isEqualTo("符合要求");
    assertThatThrownBy(
            () ->
                reviews.decide(
                    ownerId,
                    second.registrationId(),
                    new RegistrationReviewService.ReviewDecisionRequest("CONFIRM", "候选人合适")))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo("POSITION_CAPACITY_FULL");
    reviews.decide(
        ownerId,
        second.registrationId(),
        new RegistrationReviewService.ReviewDecisionRequest("WAITLIST", "等待空位"));
    assertThat(cycles.selectById(second.cycleId()).getWaitlistSequence()).isNull();
    cancellations.cancel(
        memberIds.get(0),
        first.registrationId(),
        new RegistrationCancellationService.CancellationRequest(null));
    assertThat(pendingOffers(fixture.positionId())).isZero();
    PromotionOffer invitation =
        promotions.createManualOffer(
            ownerId, second.registrationId(), new PromotionService.PromotionRequest("适合空缺岗位"));
    promotions.respond(
        memberIds.get(1),
        invitation.getId(),
        new PromotionService.PromotionResponseRequest("ACCEPT"));
    assertThat(cycles.countConfirmed(fixture.positionId())).isEqualTo(1);
  }

  private String cancelOutcome(Long userId, Long registrationId) {
    try {
      return cancellations
          .cancel(
              userId, registrationId, new RegistrationCancellationService.CancellationRequest(null))
          .getStatus();
    } catch (BusinessException exception) {
      assertThat(exception.status().value()).isEqualTo(409);
      return "CONFLICT";
    }
  }

  private String responseOutcome(Long userId, Long offerId) {
    try {
      return promotions
          .respond(userId, offerId, new PromotionService.PromotionResponseRequest("ACCEPT"))
          .getStatus();
    } catch (BusinessException exception) {
      assertThat(exception.status().value()).isEqualTo(409);
      return "CONFLICT";
    }
  }

  private String submissionOutcome(Long userId, Fixture fixture) {
    try {
      submit(userId, fixture);
      return "SUCCESS";
    } catch (BusinessException exception) {
      return exception.code();
    }
  }

  private RegistrationResult submit(Long userId, Fixture fixture) {
    return submissions.submit(
        userId,
        fixture.activityId(),
        new SubmitRegistrationRequest(
            fixture.positionId(),
            List.of(new AnswerInput("ACTIVITY", fixture.questionId(), TextNode.valueOf("接受培训")))));
  }

  private void createOrganizationAndMembers() {
    AppUser platformAdmin = user("admin", "PLATFORM_ADMIN");
    outsiderId = platformAdmin.getId();
    ownerId = user("owner", "USER").getId();
    var organization =
        organizations.create(
            platformAdmin.getId(),
            "PLATFORM_ADMIN",
            new OrganizationService.CreateOrganizationRequest(
                prefix, "isolated Stage 2 acceptance", ownerId));
    organizationId = organization.getId();
    organizationIds.add(organizationId);
    var invitation =
        organizations.createInvite(
            ownerId,
            organizationId,
            new CreateInviteRequest(101, Instant.now().plus(1, ChronoUnit.DAYS)));
    for (int index = 0; index < 101; index++) {
      AppUser member = user("m" + index, "USER");
      organizations.joinByInvite(member.getId(), invitation.code());
      memberIds.add(member.getId());
    }
  }

  private Fixture createActivity(String mode, int capacity) {
    LocalDateTime now = LocalDateTime.now(clock);
    var activity =
        activities.create(
            ownerId,
            organizationId,
            new ActivityService.ActivityRequest(
                prefix + "-" + mode,
                "acceptance",
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
                "primary", "acceptance", capacity, mode, 30, null, null, null));
    var secondPosition =
        activities.addPosition(
            ownerId,
            activity.getId(),
            new ActivityService.PositionRequest(
                "secondary", "acceptance", 1, mode, 30, null, null, null));
    var question =
        questions.createActivityQuestion(
            ownerId,
            activity.getId(),
            new ActivityQuestionService.QuestionRequest("TEXT", "培训意愿", true, List.of(), 1));
    activities.publish(ownerId, activity.getId());
    return new Fixture(
        activity.getId(), position.getId(), secondPosition.getId(), question.getId());
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
    users.insert(user);
    return user;
  }

  private long count(String statement, Object... arguments) {
    return sql.queryForObject(statement, Long.class, arguments);
  }

  private long auditCount(String action) {
    return count(
        "SELECT COUNT(*) FROM audit_log WHERE organization_id = ? AND action = ?",
        organizationId,
        action);
  }

  private long pendingOffers(Long positionId) {
    return count(
        "SELECT COUNT(*) FROM promotion_offer WHERE position_id = ? AND status = 'PENDING'",
        positionId);
  }

  private PromotionOffer pendingOffer(Long positionId) {
    Long id =
        sql.queryForObject(
            "SELECT id FROM promotion_offer WHERE position_id = ? AND status = 'PENDING'",
            Long.class,
            positionId);
    return offers.selectById(id);
  }

  private <T> List<T> concurrently(List<Callable<T>> work) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(20);
    workersStopped = false;
    CountDownLatch ready = new CountDownLatch(Math.min(20, work.size()));
    CountDownLatch start = new CountDownLatch(1);
    List<Future<T>> futures = new ArrayList<>();
    try {
      for (Callable<T> task : work) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(15, TimeUnit.SECONDS))
                    throw new IllegalStateException("Start latch timed out");
                  return task.call();
                }));
      }
      assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      }
      return results;
    } finally {
      start.countDown();
      executor.shutdownNow();
      workersStopped = executor.awaitTermination(120, TimeUnit.SECONDS);
      if (!workersStopped)
        throw new IllegalStateException("Worker shutdown timed out for run " + prefix);
    }
  }

  private void cleanup() {
    // Validate all targets before any deletion. Never infer targets from a broad LIKE predicate.
    for (Long id : organizationIds) {
      assertThat(sql.queryForObject("SELECT name FROM organization WHERE id = ?", String.class, id))
          .isEqualTo(prefix);
    }
    for (AppUser user : createdUsers) {
      assertThat(user.getUsername()).startsWith(prefix + "-");
      List<String> names =
          sql.queryForList(
              "SELECT username FROM app_user WHERE id = ?", String.class, user.getId());
      assertThat(names).allMatch(user.getUsername()::equals);
    }
    for (Long id : organizationIds) {
      for (String table :
          List.of(
              "promotion_offer",
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
        sql.update("DELETE FROM " + table + " WHERE organization_id = ?", id);
        assertThat(count("SELECT COUNT(*) FROM " + table + " WHERE organization_id = ?", id))
            .isZero();
      }
      assertThat(sql.update("DELETE FROM organization WHERE id = ? AND name = ?", id, prefix))
          .isEqualTo(1);
    }
    for (AppUser user : createdUsers) {
      sql.update(
          "DELETE FROM app_user WHERE id = ? AND username = ?", user.getId(), user.getUsername());
      assertThat(count("SELECT COUNT(*) FROM app_user WHERE id = ?", user.getId())).isZero();
    }
    System.out.println(
        "Stage2 cleanup verified for run "
            + prefix
            + ": "
            + organizationIds.size()
            + " organization(s), "
            + createdUsers.size()
            + " generated users.");
  }

  private record Fixture(
      Long activityId, Long positionId, Long secondPositionId, Long questionId) {}
}
