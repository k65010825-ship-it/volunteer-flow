package com.volunteerflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.node.TextNode;
import com.volunteerflow.activity.ActivityMapper;
import com.volunteerflow.activity.ActivityPositionQuestionMapper;
import com.volunteerflow.activity.ActivityQuestionMapper;
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
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
  private final ActivityMapper activityMapper;
  private final ActivityQuestionMapper activityQuestions;
  private final ActivityPositionQuestionMapper positionQuestions;
  private final TransactionTemplate transactions;

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
      Clock clock,
      ActivityMapper activityMapper,
      ActivityQuestionMapper activityQuestions,
      ActivityPositionQuestionMapper positionQuestions,
      PlatformTransactionManager transactionManager) {
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
    this.activityMapper = activityMapper;
    this.activityQuestions = activityQuestions;
    this.positionQuestions = positionQuestions;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Test
  void publicationCountsCommittedQuestionsEvenAfterItsRepeatableReadSnapshotWasCreated()
      throws Exception {
    try {
      createOrganizationAndMembers(0);
      for (boolean positionScope : List.of(false, true)) {
        Fixture fixture = createDraftActivity("FIRST_COME", 1);
        for (int index = 0; index < 9; index++) {
          createQuestion(fixture, positionScope, "existing-" + index, index + 2);
        }
        CountDownLatch snapshotReady = new CountDownLatch(1);
        CountDownLatch mutationCommitted = new CountDownLatch(1);
        List<String> outcomes =
            concurrently(
                List.of(
                    () ->
                        transactions.execute(
                            status -> {
                              // Prime both MySQL's repeatable-read snapshot and MyBatis's session
                              // cache.
                              assertThat(
                                      activityMapper.selectById(fixture.activityId()).getStatus())
                                  .isEqualTo("DRAFT");
                              assertThat(
                                      activityQuestions.countByActivity(fixture.activityId())
                                          + positionQuestions.maxQuestionCountByActivity(
                                              fixture.activityId()))
                                  .isEqualTo(10);
                              snapshotReady.countDown();
                              await(mutationCommitted);
                              String outcome = publicationOutcome(fixture.activityId());
                              if (!"PUBLISHED".equals(outcome)) status.setRollbackOnly();
                              return outcome;
                            }),
                    () -> {
                      await(snapshotReady);
                      try {
                        createQuestion(fixture, positionScope, "concurrent-eleventh", 11);
                        return "CREATED";
                      } finally {
                        mutationCommitted.countDown();
                      }
                    }));
        assertThat(outcomes).containsExactly("TOO_MANY_REGISTRATION_QUESTIONS", "CREATED");
        assertThat(activityMapper.selectById(fixture.activityId()).getStatus()).isEqualTo("DRAFT");
        assertThat(
                activityQuestions.countByActivity(fixture.activityId())
                    + positionQuestions.maxQuestionCountByActivity(fixture.activityId()))
            .isEqualTo(11);
      }
      System.out.println(
          "Stage2 publication current-read assertions passed for both question scopes.");
    } finally {
      if (workersStopped) cleanup();
      else
        throw new IllegalStateException("Workers still active; cleanup withheld for run " + prefix);
    }
  }

  @Test
  void publicationSerializesAllSixQuestionMutationsAndKeepsThePublishedFormFrozen()
      throws Exception {
    try {
      createOrganizationAndMembers(0);
      for (String operation :
          List.of(
              "ACTIVITY_CREATE",
              "ACTIVITY_UPDATE",
              "ACTIVITY_DELETE",
              "POSITION_CREATE",
              "POSITION_UPDATE",
              "POSITION_DELETE")) {
        Fixture fixture = createDraftActivity("FIRST_COME", 1);
        Long positionQuestionId =
            questions
                .createPositionQuestion(
                    ownerId,
                    fixture.positionId(),
                    new ActivityQuestionService.QuestionRequest(
                        "TEXT", "position-original", false, List.of(), 1))
                .getId();
        assertPublicationBlocksMutation(fixture, positionQuestionId, operation);
        assertThat(activityMapper.selectById(fixture.activityId()).getStatus())
            .isEqualTo("PUBLISHED");
        assertThat(activityQuestions.selectByActivity(organizationId, fixture.activityId()))
            .singleElement()
            .satisfies(question -> assertThat(question.getTitle()).isEqualTo("培训意愿"));
        assertThat(
                positionQuestions.selectByPosition(
                    organizationId, fixture.activityId(), fixture.positionId()))
            .singleElement()
            .satisfies(question -> assertThat(question.getTitle()).isEqualTo("position-original"));
      }
      System.out.println(
          "Stage2 publication freeze assertions passed: six concurrent mutation paths rejected.");
    } finally {
      if (workersStopped) cleanup();
      else
        throw new IllegalStateException("Workers still active; cleanup withheld for run " + prefix);
    }
  }

  @Test
  void changingChoiceQuestionsToTextOrBooleanClearsStoredOptionsInBothTables() {
    try {
      createOrganizationAndMembers(0);
      Fixture fixture = createDraftActivity("FIRST_COME", 1);
      int sortOrder = 2;
      for (String type : List.of("TEXT", "BOOLEAN")) {
        var choice =
            new ActivityQuestionService.QuestionRequest(
                "SINGLE_CHOICE", "choice", true, List.of("yes", "no"), sortOrder++);
        Long activityQuestionId =
            questions.createActivityQuestion(ownerId, fixture.activityId(), choice).getId();
        Long positionQuestionId =
            questions.createPositionQuestion(ownerId, fixture.positionId(), choice).getId();
        var replacement =
            new ActivityQuestionService.QuestionRequest(
                type, "replacement", false, List.of(), sortOrder++);
        questions.updateActivityQuestion(
            ownerId, fixture.activityId(), activityQuestionId, replacement);
        questions.updatePositionQuestion(
            ownerId, fixture.positionId(), positionQuestionId, replacement);
        // Fresh JDBC reads assert actual committed columns, not the returned in-memory entities.
        for (var target :
            List.of(
                new QuestionRow("activity_question", activityQuestionId),
                new QuestionRow("activity_position_question", positionQuestionId))) {
          var row =
              sql.queryForMap(
                  "SELECT question_type, options_json FROM " + target.table() + " WHERE id = ?",
                  target.id());
          assertThat(row.get("question_type")).isEqualTo(type);
          assertThat(row.get("options_json")).as(target.table() + " " + type).isNull();
        }
      }
      System.out.println(
          "Stage2 persisted null-options assertions passed: both tables, TEXT and BOOLEAN.");
    } finally {
      cleanup();
    }
  }

  private void assertPublicationBlocksMutation(
      Fixture fixture, Long positionQuestionId, String operation) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    workersStopped = false;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch publishedInTransaction = new CountDownLatch(1);
    CountDownLatch mutationStarted = new CountDownLatch(1);
    CountDownLatch allowCommit = new CountDownLatch(1);
    try {
      Future<String> publisher =
          executor.submit(
              () -> {
                await(start);
                return transactions.execute(
                    status -> {
                      activities.publish(ownerId, fixture.activityId());
                      publishedInTransaction.countDown();
                      await(allowCommit);
                      return "PUBLISHED";
                    });
              });
      Future<String> editor =
          executor.submit(
              () -> {
                await(start);
                await(publishedInTransaction);
                mutationStarted.countDown();
                try {
                  var replacement =
                      new ActivityQuestionService.QuestionRequest(
                          "TEXT", "changed", false, List.of(), 2);
                  switch (operation) {
                    case "ACTIVITY_CREATE" ->
                        questions.createActivityQuestion(
                            ownerId, fixture.activityId(), replacement);
                    case "ACTIVITY_UPDATE" ->
                        questions.updateActivityQuestion(
                            ownerId, fixture.activityId(), fixture.questionId(), replacement);
                    case "ACTIVITY_DELETE" ->
                        questions.deleteActivityQuestion(
                            ownerId, fixture.activityId(), fixture.questionId());
                    case "POSITION_CREATE" ->
                        questions.createPositionQuestion(
                            ownerId, fixture.positionId(), replacement);
                    case "POSITION_UPDATE" ->
                        questions.updatePositionQuestion(
                            ownerId, fixture.positionId(), positionQuestionId, replacement);
                    case "POSITION_DELETE" ->
                        questions.deletePositionQuestion(
                            ownerId, fixture.positionId(), positionQuestionId);
                    default -> throw new IllegalArgumentException(operation);
                  }
                  return "MUTATED";
                } catch (BusinessException exception) {
                  return exception.code();
                }
              });
      start.countDown();
      await(mutationStarted);
      try {
        assertThatThrownBy(() -> editor.get(500, TimeUnit.MILLISECONDS))
            .as(operation + " must wait for the publication transaction")
            .isInstanceOf(TimeoutException.class);
      } finally {
        allowCommit.countDown();
      }
      assertThat(publisher.get(15, TimeUnit.SECONDS)).isEqualTo("PUBLISHED");
      assertThat(editor.get(15, TimeUnit.SECONDS)).isEqualTo("ACTIVITY_NOT_DRAFT");
    } finally {
      start.countDown();
      allowCommit.countDown();
      executor.shutdownNow();
      workersStopped = executor.awaitTermination(30, TimeUnit.SECONDS);
      if (!workersStopped)
        throw new IllegalStateException("Worker shutdown timed out for run " + prefix);
    }
  }

  private void createQuestion(Fixture fixture, boolean positionScope, String title, int sortOrder) {
    var request =
        new ActivityQuestionService.QuestionRequest("TEXT", title, false, List.of(), sortOrder);
    if (positionScope) questions.createPositionQuestion(ownerId, fixture.positionId(), request);
    else questions.createActivityQuestion(ownerId, fixture.activityId(), request);
  }

  private String publicationOutcome(Long activityId) {
    try {
      activities.publish(ownerId, activityId);
      return "PUBLISHED";
    } catch (BusinessException exception) {
      return exception.code();
    }
  }

  private void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(15, TimeUnit.SECONDS))
          .as("concurrent transaction rendezvous")
          .isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Concurrent acceptance interrupted", exception);
    }
  }

  private record QuestionRow(String table, Long id) {}

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
    createOrganizationAndMembers(101);
  }

  private void createOrganizationAndMembers(int members) {
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
    for (int index = 0; index < members; index++) {
      AppUser member = user("m" + index, "USER");
      organizations.joinByInvite(member.getId(), invitation.code());
      memberIds.add(member.getId());
    }
  }

  private Fixture createActivity(String mode, int capacity) {
    Fixture fixture = createDraftActivity(mode, capacity);
    activities.publish(ownerId, fixture.activityId());
    return fixture;
  }

  private Fixture createDraftActivity(String mode, int capacity) {
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
