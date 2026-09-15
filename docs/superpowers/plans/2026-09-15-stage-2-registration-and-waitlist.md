# VolunteerFlow Stage 2 Registration and Waitlist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the complete stage-2 registration loop: dynamic questions, first-come and review modes, cancellation, waitlists, promotion offers, member/admin pages, audit records, and MySQL concurrency proof.

**Architecture:** Keep MySQL as the source of truth and place registration orchestration in the `registration` module. The `activity` module exposes a narrow registration-policy service so registration code does not reach into activity mappers. Every state-changing path uses the documented member-then-position lock order, writes audit data in the same transaction, and remains independent of Redis and Kafka.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis-Plus 3.5, MySQL 8/InnoDB, Flyway, JUnit 5, Mockito, Vue 3, TypeScript, Axios, Vitest

**Spec:** `docs/superpowers/specs/2026-09-15-stage-2-registration-and-waitlist-design.md`

## Global Constraints

- Use Java 17; do not introduce Java 21-only language or library APIs.
- Do not add physical foreign keys or modify existing V1-V8 migrations.
- Do not change the configured MySQL or Redis host, port, database, or credentials.
- Redis and Kafka must not participate in stage-2 correctness.
- Use MyBatis-Plus for ordinary CRUD and explicit SQL for locks, capacity, queue, and conditional transitions.
- Keep annotations, declarations, and method bodies conventionally formatted; use descriptive names and comments only for non-obvious business/concurrency rules.
- Write a failing behavior test and observe the expected failure before each production change.
- Do not expose another candidate's identity or raw registration answers to ordinary members.

---

### Task 1: Dynamic registration questions and form policy

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/activity/ActivityQuestion.java`
- Create: `backend/src/main/java/com/volunteerflow/activity/ActivityPositionQuestion.java`
- Create: `backend/src/main/java/com/volunteerflow/activity/ActivityQuestionMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/activity/ActivityPositionQuestionMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/activity/ActivityQuestionService.java`
- Create: `backend/src/main/java/com/volunteerflow/activity/ActivityQuestionController.java`
- Create: `backend/src/main/java/com/volunteerflow/activity/ActivityRegistrationPolicyService.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityService.java`
- Test: `backend/src/test/java/com/volunteerflow/activity/ActivityQuestionServiceTest.java`
- Test: `backend/src/test/java/com/volunteerflow/activity/ActivityRegistrationPolicyServiceTest.java`

**Interfaces:**
- Produces: `ActivityQuestionService.createActivityQuestion(Long, Long, QuestionRequest)`
- Produces: `ActivityQuestionService.createPositionQuestion(Long, Long, QuestionRequest)`
- Produces: update/delete variants that reject published activities.
- Produces: `ActivityRegistrationPolicyService.loadForm(Long userId, Long activityId, Long positionId)` returning `RegistrationForm`.
- Produces: `ActivityRegistrationPolicyService.lockPolicyForSubmission(Long userId, Long activityId, Long positionId)` returning `RegistrationPolicy`.
- `QuestionRequest` fields: `String type`, `String title`, `boolean required`, `List<String> options`, `int sortOrder`.
- `RegistrationPolicy` fields: `Activity activity`, `ActivityPosition position`, `List<QuestionDefinition> questions`.

- [ ] **Step 1: Write failing question-rule tests**

```java
@Test
void rejectsChoiceQuestionWithDuplicateOptions() {
  QuestionRequest request =
      new QuestionRequest("SINGLE_CHOICE", "可参加培训吗", true, List.of("可以", "可以"), 1);

  assertThatThrownBy(() -> service.createActivityQuestion(7L, 10L, request))
      .isInstanceOf(BusinessException.class)
      .extracting(error -> ((BusinessException) error).code())
      .isEqualTo("INVALID_QUESTION_OPTIONS");
}

@Test
void publicationRejectsAnyPositionWithMoreThanTenCombinedQuestions() {
  when(activityQuestionMapper.countByActivity(10L)).thenReturn(6L);
  when(positionQuestionMapper.maxQuestionCountByActivity(10L)).thenReturn(5L);

  assertThatThrownBy(() -> activityService.publish(7L, 10L))
      .isInstanceOf(BusinessException.class)
      .extracting(error -> ((BusinessException) error).code())
      .isEqualTo("TOO_MANY_REGISTRATION_QUESTIONS");
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd backend && .\mvnw.cmd -Dtest=ActivityQuestionServiceTest,ActivityRegistrationPolicyServiceTest test`

Expected: test compilation fails because question types and services do not exist.

- [ ] **Step 3: Implement question entities, validation, CRUD, and policy reads**

Use Lombok `@Getter/@Setter`, `@TableName`, and `@TableId(type = IdType.ASSIGN_ID)` consistently with existing entities. Serialize options through the existing Jackson `ObjectMapper`; never concatenate JSON manually.

Required mapper methods:

```java
List<ActivityQuestion> selectByActivity(Long organizationId, Long activityId);
List<ActivityPositionQuestion> selectByPosition(Long organizationId, Long activityId, Long positionId);
long countByActivity(Long activityId);
long maxQuestionCountByActivity(Long activityId);
ActivityPosition selectByIdForUpdate(Long positionId);
```

`lockPolicyForSubmission` must verify membership, load a `PUBLISHED` activity, verify that the position is active and belongs to it, lock the position with `FOR UPDATE`, and return the ordered combined question list.

- [ ] **Step 4: Run focused and existing activity tests**

Run: `cd backend && .\mvnw.cmd -Dtest=ActivityQuestionServiceTest,ActivityRegistrationPolicyServiceTest,ActivityServiceTest test`

Expected: all listed tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/activity backend/src/test/java/com/volunteerflow/activity
git commit -m "feat: add dynamic registration questions"
```

---

### Task 2: Registration persistence model and first-come submission

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/registration/Registration.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationCycle.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationAnswer.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionOffer.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationCycleMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationAnswerMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionOfferMapper.java`
- Modify: `backend/src/main/java/com/volunteerflow/organization/OrganizationMemberMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationAnswerValidator.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationSubmissionService.java`
- Test: `backend/src/test/java/com/volunteerflow/registration/RegistrationAnswerValidatorTest.java`
- Test: `backend/src/test/java/com/volunteerflow/registration/RegistrationSubmissionServiceTest.java`

**Interfaces:**
- Consumes: `ActivityRegistrationPolicyService.lockPolicyForSubmission(...)`.
- Produces: `RegistrationSubmissionService.submit(Long userId, Long activityId, SubmitRegistrationRequest request)`.
- `SubmitRegistrationRequest`: `Long positionId`, `List<AnswerInput> answers`.
- `AnswerInput`: `String questionScope`, `Long questionId`, `JsonNode answer`.
- Produces: `RegistrationResult(Long registrationId, Long cycleId, String status, Long waitlistSequence, Integer currentPosition)`.

- [ ] **Step 1: Write failing validator and first-come tests**

```java
@Test
void requiredQuestionMustHaveAnAnswer() {
  QuestionDefinition required =
      new QuestionDefinition("ACTIVITY", 1L, "TEXT", "特长", true, List.of(), 1);

  assertThatThrownBy(() -> validator.validate(List.of(required), List.of()))
      .isInstanceOf(BusinessException.class)
      .extracting(error -> ((BusinessException) error).code())
      .isEqualTo("REQUIRED_ANSWER_MISSING");
}

@Test
void firstComeSubmissionJoinsWaitlistWhenPositionIsFull() {
  when(cycleMapper.countConfirmed(20L)).thenReturn(20L);
  when(offerMapper.countActiveReservations(20L)).thenReturn(0L);
  position.setCapacity(20);
  position.setNextWaitlistSequence(1L);

  RegistrationResult result = service.submit(21L, 10L, requestFor(20L));

  assertThat(result.status()).isEqualTo("WAITLISTED");
  assertThat(result.waitlistSequence()).isEqualTo(1L);
  assertThat(position.getNextWaitlistSequence()).isEqualTo(2L);
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd backend && .\mvnw.cmd -Dtest=RegistrationAnswerValidatorTest,RegistrationSubmissionServiceTest test`

Expected: compilation fails because registration production types do not exist.

- [ ] **Step 3: Implement entities, explicit SQL, answer validation, and submission transaction**

Add these lock/count methods:

```java
OrganizationMember selectActiveMemberForUpdate(Long userId, Long orgId);
Registration selectByActivityAndUser(Long activityId, Long userId);
RegistrationCycle selectActiveCycleForUpdate(Long registrationId);
long countConfirmed(Long positionId);
long countActiveWaitlistBefore(Long positionId, long sequence);
long countActiveWaitlisted(Long positionId);
long countActiveReservations(Long positionId);
```

`submit` must:

1. Load membership and lock it with `FOR UPDATE`.
2. Load and lock policy/position.
3. Verify `PUBLISHED` and `registrationStartAt <= now < registrationEndAt`.
4. Reject an existing non-terminal cycle with `REGISTRATION_ALREADY_ACTIVE`.
5. Create or reuse the stable registration and increment `lastCycleNumber`.
6. Validate all submitted answers against server-side definitions.
7. Set `CONFIRMED` or allocate `WAITLISTED` for `FIRST_COME`; set `PENDING_REVIEW` for `REVIEW`.
8. Insert answer snapshots and `registration.submitted` audit data in the same transaction.

Translate duplicate-key races to `409 REGISTRATION_CONFLICT` in the service boundary.

- [ ] **Step 4: Run focused tests**

Run: `cd backend && .\mvnw.cmd -Dtest=RegistrationAnswerValidatorTest,RegistrationSubmissionServiceTest test`

Expected: all tests pass, including direct confirmation, waitlisting, review mode, duplicate active registration, closed window, and invalid answers.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/registration backend/src/main/java/com/volunteerflow/organization/OrganizationMemberMapper.java backend/src/test/java/com/volunteerflow/registration
git commit -m "feat: implement registration submission"
```

---

### Task 3: Registration queries, privacy, and waitlist position

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationQueryService.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationViews.java`
- Test: `backend/src/test/java/com/volunteerflow/registration/RegistrationQueryServiceTest.java`

**Interfaces:**
- Produces: `RegistrationQueryService.getOwn(Long userId, Long registrationId)`.
- Produces: `RegistrationQueryService.listOwn(Long userId)`.
- Produces: `RegistrationQueryService.listForPosition(Long actorId, Long positionId, String status, int page, int size)`.
- `OwnRegistrationView` includes the active/latest cycle, own answers, current waitlist position, waitlist count, and own pending offer.
- `ManagedRegistrationView` includes member profile fields required for review, answers, status, review metadata, and offer metadata.

- [ ] **Step 1: Write failing privacy and position tests**

```java
@Test
void memberViewCalculatesPositionWithoutReturningOtherCandidates() {
  when(cycleMapper.countActiveWaitlistBefore(20L, 8L)).thenReturn(2L);
  when(cycleMapper.countActiveWaitlisted(20L)).thenReturn(6L);

  OwnRegistrationView view = service.getOwn(21L, 100L);

  assertThat(view.currentWaitlistPosition()).isEqualTo(3);
  assertThat(view.waitlistCount()).isEqualTo(6);
  assertThat(view).hasNoNullFieldsOrPropertiesExcept("pendingOffer");
}

@Test
void anotherMemberCannotReadRegistrationDetail() {
  assertThatThrownBy(() -> service.getOwn(99L, 100L))
      .isInstanceOf(BusinessException.class)
      .extracting(error -> ((BusinessException) error).status())
      .isEqualTo(HttpStatus.NOT_FOUND);
}
```

- [ ] **Step 2: Run test and verify RED**

Run: `cd backend && .\mvnw.cmd -Dtest=RegistrationQueryServiceTest test`

Expected: compilation fails because the query service and views do not exist.

- [ ] **Step 3: Implement member/admin projections and paged mapper queries**

Use DTO records rather than returning persistence entities directly. Member queries must filter by `registration.user_id`; admin queries must call `registration:review` and verify the position belongs to the visible organization. Clamp page size to 1-100.

- [ ] **Step 4: Run focused tests**

Run: `cd backend && .\mvnw.cmd -Dtest=RegistrationQueryServiceTest test`

Expected: own detail/list, queue position, admin projection, pagination, cross-user, and cross-organization tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/registration backend/src/test/java/com/volunteerflow/registration
git commit -m "feat: add registration status queries"
```

---

### Task 4: Manual review and candidate-pool decisions

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationReviewService.java`
- Test: `backend/src/test/java/com/volunteerflow/registration/RegistrationReviewServiceTest.java`

**Interfaces:**
- Produces: `RegistrationReviewService.decide(Long actorId, Long registrationId, ReviewDecisionRequest request)`.
- `ReviewDecisionRequest`: `String decision`, `String reason`.
- Allowed decisions: `CONFIRM`, `WAITLIST`, `REJECT`.

- [ ] **Step 1: Write failing decision and capacity tests**

```java
@Test
void confirmRequiresAvailableCapacity() {
  when(cycleMapper.countConfirmed(20L)).thenReturn(20L);
  when(offerMapper.countActiveReservations(20L)).thenReturn(0L);

  assertThatThrownBy(() -> service.decide(7L, 100L, decision("CONFIRM")))
      .isInstanceOf(BusinessException.class)
      .extracting(error -> ((BusinessException) error).code())
      .isEqualTo("POSITION_CAPACITY_FULL");
}

@Test
void waitlistDecisionCreatesUnorderedCandidate() {
  RegistrationCycle result = service.decide(7L, 100L, decision("WAITLIST"));

  assertThat(result.getStatus()).isEqualTo("WAITLISTED");
  assertThat(result.getWaitlistSequence()).isNull();
  assertThat(result.getReviewedBy()).isEqualTo(7L);
}
```

- [ ] **Step 2: Run test and verify RED**

Run: `cd backend && .\mvnw.cmd -Dtest=RegistrationReviewServiceTest test`

Expected: compilation fails because the review service does not exist.

- [ ] **Step 3: Implement transactional review**

Require `registration:review`, follow member-position-cycle lock order, accept only `PENDING_REVIEW`, verify the position mode is `REVIEW`, and record reviewer, review time, reason, new status, and `registration.reviewed` audit data atomically. A `CONFIRM` decision must count confirmed cycles plus active offers under the position lock.

- [ ] **Step 4: Run focused tests**

Run: `cd backend && .\mvnw.cmd -Dtest=RegistrationReviewServiceTest test`

Expected: all three decisions, capacity conflict, repeated review, incorrect mode, permission, and organization-isolation tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/registration/RegistrationReviewService.java backend/src/test/java/com/volunteerflow/registration/RegistrationReviewServiceTest.java
git commit -m "feat: add manual registration review"
```

---

### Task 5: Cancellation and promotion offers

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionService.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationCancellationService.java`
- Test: `backend/src/test/java/com/volunteerflow/registration/PromotionServiceTest.java`
- Test: `backend/src/test/java/com/volunteerflow/registration/RegistrationCancellationServiceTest.java`

**Interfaces:**
- Produces: `RegistrationCancellationService.cancel(Long userId, Long registrationId, CancellationRequest request)`.
- Produces: `PromotionService.offerNextFirstCome(Long positionId, Long createdBy, String reason)` for internal transaction use.
- Produces: `PromotionService.createManualOffer(Long actorId, Long registrationId, PromotionRequest request)`.
- Produces: `PromotionService.respond(Long userId, Long offerId, PromotionResponseRequest request)`.
- Decisions: `ACCEPT`, `DECLINE`.

- [ ] **Step 1: Write failing cancellation and offer tests**

```java
@Test
void cancelingConfirmedFirstComeRegistrationOffersPlaceToQueueHead() {
  RegistrationCycle canceled = confirmedCycle(20L);
  RegistrationCycle queueHead = waitlistedCycle(21L, 1L);
  when(cycleMapper.selectFirstWaitlistedForUpdate(20L)).thenReturn(queueHead);

  service.cancel(11L, 100L, new CancellationRequest(null));

  assertThat(canceled.getStatus()).isEqualTo("CANCELED");
  verify(offerMapper).insert(argThat(offer -> offer.getRegistrationCycleId().equals(21L)));
}

@Test
void expiredOfferCannotBeAccepted() {
  offer.setStatus("PENDING");
  offer.setExpiresAt(LocalDateTime.of(2026, 9, 15, 7, 59));

  assertThatThrownBy(() -> service.respond(21L, 300L, response("ACCEPT")))
      .isInstanceOf(BusinessException.class)
      .extracting(error -> ((BusinessException) error).code())
      .isEqualTo("PROMOTION_OFFER_EXPIRED");
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd backend && .\mvnw.cmd -Dtest=PromotionServiceTest,RegistrationCancellationServiceTest test`

Expected: compilation fails because cancellation and promotion services do not exist.

- [ ] **Step 3: Implement cancellation and offer state transitions**

Cancellation must resolve a null free-cancel deadline to the activity start, require a reason only after the effective deadline, cancel a pending offer when present, and audit the result. `offerNextFirstCome` must select the smallest sequenced `WAITLISTED` cycle under lock and create exactly one `PENDING` offer. Manual offers require `registration:promote`, a `REVIEW` position, a waitlisted unordered candidate, and available capacity.

`respond` must first read the offer without a lock to obtain its related IDs, then lock the registration owner's membership, position, cycle, and offer in that order. It must re-check ownership, status, and expiry after locking, update offer and cycle atomically, and automatically offer the next first-come candidate after decline.

- [ ] **Step 4: Run focused tests**

Run: `cd backend && .\mvnw.cmd -Dtest=PromotionServiceTest,RegistrationCancellationServiceTest test`

Expected: free/late cancellation, missing reason, pending-offer cancellation, queue-head selection, manual offer, accept, decline, expiry, repeat response, and audit tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/registration backend/src/test/java/com/volunteerflow/registration
git commit -m "feat: implement cancellation and promotion"
```

---

### Task 6: Idempotent promotion-expiry scheduler

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionExpiryProperties.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionExpiryJob.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionExpiryService.java`
- Modify: `backend/src/main/java/com/volunteerflow/VolunteerFlowApplication.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/volunteerflow/registration/PromotionExpiryJobTest.java`

**Interfaces:**
- Produces: `PromotionExpiryJob.expireBatch()` returning the number processed.
- Produces: `PromotionExpiryService.expireOne(Long offerId)` using `REQUIRES_NEW` and returning whether it changed the offer.
- Mapper produces: `List<Long> selectExpiredIds(LocalDateTime now, int batchSize)` without row locks.
- Configuration prefix: `volunteerflow.registration.promotion-expiry` with `scan-delay=30s`, `batch-size=100`.

- [ ] **Step 1: Write failing idempotency test**

```java
@Test
void expiresPendingOfferOnceAndPromotesNextFirstComeCandidate() {
  when(offerMapper.selectExpiredIds(now, 100)).thenReturn(List.of(300L), List.of());
  when(expiryService.expireOne(300L)).thenReturn(true, false);

  assertThat(job.expireBatch()).isEqualTo(1);
  assertThat(job.expireBatch()).isZero();
  verify(expiryService, times(2)).expireOne(300L);
}

@Test
void expireOneRechecksStatusAfterTakingLocks() {
  offer.setStatus("ACCEPTED");

  assertThat(expiryService.expireOne(300L)).isFalse();
  verify(promotionService, never()).offerNextFirstCome(anyLong(), any(), anyString());
}
```

- [ ] **Step 2: Run test and verify RED**

Run: `cd backend && .\mvnw.cmd -Dtest=PromotionExpiryJobTest test`

Expected: compilation fails because the expiry job does not exist.

- [ ] **Step 3: Implement configured scheduler and one-transaction batch**

Enable scheduling on the application, bind validated duration/batch properties, disable automatic scheduling under the `test` profile, and keep `expireBatch()` callable in tests. The job reads candidate IDs without locks. `expireOne` opens a new transaction, obtains related IDs from a non-locking snapshot, then locks owner membership, position, cycle, and offer in the global order and rechecks `PENDING` plus expiry before changing data. Update offer, cycle, and audit before selecting the next first-come candidate.

- [ ] **Step 4: Run focused tests**

Run: `cd backend && .\mvnw.cmd -Dtest=PromotionExpiryJobTest,VolunteerFlowApplicationTests test`

Expected: idempotency, batch limit, review-mode no-auto-selection, and context startup tests pass.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/registration backend/src/main/java/com/volunteerflow/VolunteerFlowApplication.java backend/src/main/resources/application.yml backend/src/test/java/com/volunteerflow/registration
git commit -m "feat: expire promotion offers safely"
```

---

### Task 7: Registration REST API and security contract

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/registration/RegistrationController.java`
- Create: `backend/src/test/java/com/volunteerflow/registration/RegistrationControllerTest.java`
- Modify: `backend/src/main/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandler.java`
- Modify: `backend/src/test/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandlerTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/infrastructure/security/JwtSecurityFlowTest.java`

**Interfaces:**
- Exposes every endpoint listed in the approved stage-2 spec under `/api/v1`.
- All successful bodies use `ApiResponse.of(data)`.
- Creates return `201`; state commands with a representation return `200`; deletes without content return `204`.

- [ ] **Step 1: Write failing MVC contract tests**

```java
@Test
void submitUsesAuthenticatedUserInsteadOfClientSuppliedUserId() throws Exception {
  mvc.perform(
          post("/api/v1/activities/10/registrations")
              .with(authentication(currentUser(21L)))
              .contentType(APPLICATION_JSON)
              .content("{\"positionId\":20,\"answers\":[]}"))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

  verify(submissionService).submit(eq(21L), eq(10L), any());
}

@Test
void anonymousRegistrationIsRejected() throws Exception {
  mvc.perform(post("/api/v1/activities/10/registrations").contentType(APPLICATION_JSON).content("{}"))
      .andExpect(status().isUnauthorized());
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd backend && .\mvnw.cmd -Dtest=RegistrationControllerTest,JwtSecurityFlowTest test`

Expected: registration routes are missing and the authenticated contract test returns 404.

- [ ] **Step 3: Implement controllers and validated request records**

Use `@AuthenticationPrincipal CurrentUser currentUser`, `@Valid`, explicit path variable names, request-size limits, and enum-pattern validation. Keep authorization and ownership decisions in services. Map `DuplicateKeyException` to `409 REGISTRATION_CONFLICT` and `OptimisticLockingFailureException` to `409 CONCURRENT_MODIFICATION` without returning SQL text.

- [ ] **Step 4: Run API/security and full backend tests**

Run: `cd backend && .\mvnw.cmd clean verify`

Expected: all backend tests pass and the executable jar is built with Java 17.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/volunteerflow/registration backend/src/main/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandler.java backend/src/test/java/com/volunteerflow/registration backend/src/test/java/com/volunteerflow/infrastructure/security backend/src/test/java/com/volunteerflow/infrastructure/web/GlobalExceptionHandlerTest.java
git commit -m "feat: expose registration APIs"
```

---

### Task 8: Member registration form and status experience

**Files:**
- Create: `frontend/src/api/registrations.ts`
- Modify: `frontend/src/api/types.ts`
- Create: `frontend/src/components/RegistrationQuestionField.vue`
- Modify: `frontend/src/views/ActivityDetailView.vue`
- Create: `frontend/src/views/MyRegistrationsView.vue`
- Create: `frontend/src/views/RegistrationStatusView.vue`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/components/AppShell.vue`
- Modify: `frontend/src/styles.css`
- Test: `frontend/src/components/RegistrationQuestionField.test.ts`
- Test: `frontend/src/views/ActivityDetailView.test.ts`
- Test: `frontend/src/views/RegistrationStatusView.test.ts`

**Interfaces:**
- API functions: `getRegistrationForm`, `submitRegistration`, `listMyRegistrations`, `getRegistration`, `cancelRegistration`, `respondToPromotionOffer`.
- Route additions: `/registrations`, `/registrations/:id`.
- Question component uses `modelValue: unknown` and emits `update:modelValue`.

- [ ] **Step 1: Write failing component and flow tests**

```ts
it("renders choices and emits the selected value", async () => {
  const wrapper = mount(RegistrationQuestionField, {
    props: {
      question: question("SINGLE_CHOICE", ["可以", "不可以"]),
      modelValue: null,
    },
  });

  await wrapper.get('input[value="可以"]').setValue();
  expect(wrapper.emitted("update:modelValue")?.[0]).toEqual(["可以"]);
});

it("submits the selected position and dynamic answers", async () => {
  await wrapper.get('[data-test="submit-registration"]').trigger("click");
  expect(submitRegistration).toHaveBeenCalledWith("10", {
    positionId: "20",
    answers: expectedAnswers,
  });
});
```

- [ ] **Step 2: Run tests and verify RED**

Run: `cd frontend && npm test -- RegistrationQuestionField.test.ts ActivityDetailView.test.ts RegistrationStatusView.test.ts`

Expected: imports fail because the new API, component, and pages do not exist.

- [ ] **Step 3: Implement member UI**

Replace the stage-2 placeholder in `ActivityDetailView.vue`. Load the form after position selection, render TEXT/SINGLE_CHOICE/MULTIPLE_CHOICE/BOOLEAN fields, validate required values, disable duplicate submissions, and navigate to status on success. Status view must calculate countdown from server `expiresAt`, refresh server state after accept/decline, show position only for sequenced waitlists, and request a reason for late cancellation when the backend indicates it is required.

- [ ] **Step 4: Run frontend verification**

Run: `cd frontend && npm test -- --run && npm run typecheck && npm run build`

Expected: component/view tests pass, TypeScript reports no errors, and Vite production build succeeds.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src
git commit -m "feat: add member registration experience"
```

---

### Task 9: Admin review UI and MySQL concurrency acceptance

**Files:**
- Create: `frontend/src/views/RegistrationManagementView.vue`
- Modify: `frontend/src/api/registrations.ts`
- Modify: `frontend/src/router/index.ts`
- Modify: `frontend/src/styles.css`
- Create: `frontend/src/views/RegistrationManagementView.test.ts`
- Create: `backend/src/test/java/com/volunteerflow/Stage2VmAcceptanceIT.java`
- Modify: `README.md`

**Interfaces:**
- API functions: `listPositionRegistrations`, `reviewRegistration`, `createPromotionOffer`.
- Route: `/positions/:positionId/registrations`.
- Acceptance test drives services against the configured MySQL and deletes only rows carrying its unique test IDs in a `finally` block.

- [ ] **Step 1: Write failing admin and concurrent acceptance tests**

```ts
it("offers only valid review decisions for pending candidates", async () => {
  const wrapper = mountManagementWithStatus("PENDING_REVIEW");
  expect(wrapper.get('[data-test="confirm"]').exists()).toBe(true);
  expect(wrapper.get('[data-test="waitlist"]').exists()).toBe(true);
  expect(wrapper.get('[data-test="reject"]').exists()).toBe(true);
  expect(wrapper.find('[data-test="promote"]').exists()).toBe(false);
});
```

```java
@Test
void oneHundredConcurrentUsersNeverOversellTwentyPlaces() throws Exception {
  List<RegistrationResult> results = submitConcurrently(100, activityId, positionId);

  assertThat(results).filteredOn(result -> result.status().equals("CONFIRMED")).hasSize(20);
  assertThat(results).filteredOn(result -> result.status().equals("WAITLISTED")).hasSize(80);
  assertThat(cycleMapper.countConfirmed(positionId)).isEqualTo(20);
  assertThat(cycleMapper.selectWaitlistSequences(positionId)).doesNotHaveDuplicates();
}
```

- [ ] **Step 2: Run tests and verify RED**

Run frontend: `cd frontend && npm test -- RegistrationManagementView.test.ts`

Run backend: `cd backend && .\mvnw.cmd -Dtest=Stage2VmAcceptanceIT test`

Expected: frontend import fails and backend acceptance test fails to compile before fixtures/helpers are implemented.

- [ ] **Step 3: Implement admin page, acceptance fixtures, and README status**

The admin page must filter by status, display review answers, require a reason, show review actions only for `PENDING_REVIEW`, and show manual promotion only for unordered `WAITLISTED` review candidates. The VM test must create isolated users/organization/activity, use a fixed 20-thread executor plus start latch, assert database facts after all futures complete, and delete only records linked to its generated organization/activity/user IDs in a `finally` block. Cleanup SQL runs child tables before parent tables and never uses a broad predicate.

Update README stage 2 only after the acceptance assertions pass. Record measured test counts but do not invent latency, throughput, or user evidence.

- [ ] **Step 4: Run final verification**

```powershell
cd backend
.\mvnw.cmd clean verify
.\mvnw.cmd -Dtest=Stage2VmAcceptanceIT test
cd ..\frontend
npm test -- --run
npm run typecheck
npm run build
cd ..
git diff --check
```

Expected:

- Backend unit/security/context suite passes and jar packaging succeeds.
- Configured-MySQL acceptance proves 20 confirmed, 80 waitlisted, no duplicate active registrations, unique waitlist numbers, cancellation promotion, acceptance, and audit records.
- Frontend tests, type checking, and production build pass.
- No whitespace errors or uncommitted generated artifacts exist.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src backend/src/test/java/com/volunteerflow/Stage2VmAcceptanceIT.java README.md
git commit -m "feat: complete stage 2 registration loop"
```
