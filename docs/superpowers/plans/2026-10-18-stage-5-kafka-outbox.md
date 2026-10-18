# VolunteerFlow Stage 5 Kafka and Transactional Outbox Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace synchronous notification creation with a Kafka-backed Transactional Outbox pipeline, add idempotent consumption and indexed promotion reconciliation, and prove outage recovery without weakening MySQL business correctness.

**Architecture:** Business services append recipient-specific, versioned domain events to MySQL in their existing transactions. A leased Outbox relay publishes to three Kafka topics outside database transactions; a record-level consumer writes `consumed_event` and `notification` atomically. A separate MySQL task queue reconciles only positions explicitly marked dirty, so waitlist recovery never scans all activities.

**Tech Stack:** Java 17, Spring Boot 3.5.16, Spring Kafka, MyBatis-Plus 3.5.17, MySQL 8, Kafka KRaft, JUnit 5, Testcontainers, Docker Compose, Micrometer

**Spec:** `docs/superpowers/specs/2026-10-18-stage-5-kafka-outbox-design.md`

## Global Constraints

- Keep Java at version 17 and retain the modular monolith; do not introduce microservices, Kafka Streams, Schema Registry, Debezium, chat, or AI.
- Kafka never participates in registration, cancellation, review, promotion, or check-in transactions. MySQL remains the business system of record.
- Migrate every current synchronous notification producer; notification query/read APIs remain unchanged.
- Delivery semantics are at least once. Database uniqueness and idempotency are required even when Kafka producer idempotence is enabled.
- Use exactly three configurable topics: `volunteerflow.activity-events.v1`, `volunteerflow.registration-events.v1`, and `volunteerflow.checkin-events.v1`.
- Use `organizationId:activityId` as the Kafka key and version every `eventType` with `.v1`.
- Never put credentials, JWTs, full contact details, persistence entities, or full stack traces in events, DLT headers, or logs.
- Add no physical foreign keys. Use unique constraints, indexed access paths, and business-layer validation.
- All scanners use an indexed due-time/keyset query, a fixed batch limit, and a processing lease. No activity, position, Outbox, or retention full-table scan is allowed.
- The restored demonstration database keeps `SPRING_FLYWAY_ENABLED=false`; never edit its failed Flyway history. Live migration requires backup, fingerprint validation, reviewed additive SQL, and before/after checks.
- Kafka must not block application startup or enter the core readiness group. Compose must not stop, recreate, or replace the existing MySQL and Redis containers.
- Follow test-driven development for every production change: focused RED, minimal GREEN, focused verification, then the full backend suite.

## Review Focus

- Kafka is unavailable at application startup or publish time: the core API starts, the business transaction succeeds, and the Outbox row remains retryable.
- The relay publishes successfully and crashes before updating MySQL: redelivery increments the duplicate metric but creates no second notification.
- A lease expires while a stale worker is still running: only the current lease owner can mark the row published, retryable, dead, or complete.
- Malformed JSON or an unsupported event version: the record reaches DLT after three attempts, creates no `consumed_event`, and does not poison-loop.
- A position is marked dirty while reconciliation is processing an older version: the higher `request_version` remains pending and is processed later.

---

## File map

- `infrastructure/event`: stable event envelope, topic routing, Outbox persistence, relay, Kafka adapter, configuration, metrics, and component health.
- `notification`: Kafka listener and transactional event-to-notification handler; existing query/controller code remains here.
- `registration`: promotion reconciliation task, requester, service, and job because they reuse registration lock order and promotion rules.
- `db/migration`: V10 contains only additive Stage 5 schema and indexes.
- `deploy/stage5`: run-scoped Kafka KRaft deployment and outage/recovery evidence tooling.

### Task 1: Add Stage 5 schema and persistence contracts

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__add_stage5_event_pipeline.sql`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/OutboxEvent.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/OutboxEventMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/ConsumedEvent.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/ConsumedEventMapper.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionReconcileTask.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionReconcileTaskMapper.java`
- Modify: `backend/src/test/java/com/volunteerflow/infrastructure/persistence/FlywayMigrationContractTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/infrastructure/persistence/LombokEntityContractTest.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/event/Stage5PersistenceContractTest.java`

**Interfaces:**
- Produces: `OutboxEventMapper.selectClaimableForUpdate(LocalDateTime now, int limit): List<OutboxEvent>`, `claim(List<Long> ids, String workerId, LocalDateTime lockedUntil, LocalDateTime now): int`, `markPublished(Long id, String workerId, int partition, long offset, LocalDateTime publishedAt): int`, `markRetry(Long id, String workerId, int retryCount, LocalDateTime nextRetryAt, String lastError): int`, and `markDead(Long id, String workerId, String lastError): int`.
- Produces retention methods `selectPublishedIdsBefore(LocalDateTime cutoff, LocalDateTime cursorTime, Long cursorId, int limit): List<Long>` and `deletePublishedByIds(List<Long> ids): int`.
- Produces: `ConsumedEventMapper.insertIfAbsent(ConsumedEvent event): int`, returning `1` for first processing and `0` for a duplicate.
- Produces: `PromotionReconcileTaskMapper.upsertRequest(Long organizationId, Long positionId, LocalDateTime nextRunAt): int`, `selectClaimableForUpdate(LocalDateTime now, int limit): List<PromotionReconcileTask>`, `claim(Long positionId, long expectedRequestVersion, String workerId, LocalDateTime lockedUntil, LocalDateTime now): int`, `markProcessed(Long positionId, long claimedVersion, String workerId, LocalDateTime now): int`, and `markRetry(Long positionId, long claimedVersion, String workerId, int attemptCount, LocalDateTime nextRunAt, String lastError): int`.

- [ ] **Step 1: Write failing migration and mapper contract tests**

Add tests named `stage5MigrationAddsLeasedOutboxAndConsumerIdempotency`, `stage5MigrationAddsIndexedPromotionReconciliation`, `outboxUpdatesRequireTheCurrentLeaseOwner`, and `consumedEventInsertIsIdempotent`. Assert the exact columns, unique keys, status/due/id index prefixes, lack of `FOREIGN KEY`, fixed `LIMIT`, and owner predicates on terminal updates.

- [ ] **Step 2: Run the contracts and verify RED**

Run: `cd backend && .\mvnw.cmd -q -Dtest=FlywayMigrationContractTest,LombokEntityContractTest,Stage5PersistenceContractTest test`

Expected: FAIL because V10 and Stage 5 persistence types do not exist.

- [ ] **Step 3: Add V10 and the persistence types**

Extend `outbox_event` with the seven spec fields and both indexes; create `consumed_event` with unique `(consumer_name,event_id)`; create `promotion_reconcile_task` with one row per position and the due-work index. Implement the exact mapper methods above with MySQL 8 `FOR UPDATE SKIP LOCKED`, bounded IDs, and lease-owner conditions. Do not add physical foreign keys.

- [ ] **Step 4: Verify focused and full GREEN**

Run: `cd backend && .\mvnw.cmd -q -Dtest=FlywayMigrationContractTest,LombokEntityContractTest,Stage5PersistenceContractTest test`

Run: `cd backend && .\mvnw.cmd test`

Expected: focused tests and the full backend suite pass with zero failures and errors.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V10__add_stage5_event_pipeline.sql backend/src/main/java/com/volunteerflow/infrastructure/event backend/src/main/java/com/volunteerflow/registration/PromotionReconcileTask.java backend/src/main/java/com/volunteerflow/registration/PromotionReconcileTaskMapper.java backend/src/test/java/com/volunteerflow/infrastructure
git commit -m "feat(stage5): add event pipeline persistence"
```

### Task 2: Define event contracts and the transaction-local Outbox publisher

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/DomainEventEnvelope.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/DomainEventCommand.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/DomainEventPayload.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/NotificationIntentPayload.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/CheckinStatusPayload.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/DomainEventCodec.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/NotificationEventTemplate.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/DomainEventTypes.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/DomainEventPublisher.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/EventTopicRouter.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/TransactionalOutboxEventPublisher.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/event/TransactionalOutboxEventPublisherTest.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/event/EventTopicRouterTest.java`

**Interfaces:**
- Produces: `DomainEventPublisher.publish(DomainEventCommand command): UUID` and `publishMany(Collection<Long> userIds, NotificationEventTemplate template): int`.
- `DomainEventCommand` contains `organizationId`, `activityId`, `aggregateType`, `aggregateId`, `eventType`, and a `DomainEventPayload`. `DomainEventPayload` is a Java 17 sealed interface implemented initially by `NotificationIntentPayload` and `CheckinStatusPayload`.
- `NotificationIntentPayload` contains `userId`, `notificationType`, `title`, `content`, `businessType`, and `businessId`.
- `CheckinStatusPayload` contains `userId`, `sessionId`, `checkinRecordId`, and `method`; it represents a domain fact without forcing creation of a notification.
- `NotificationEventTemplate` contains the same fields except `userId`; `publishMany` creates one event per distinct recipient so fan-out never creates an oversized recipient-list payload.
- `DomainEventEnvelope` contains `eventId`, `eventType`, `occurredAt`, `organizationId`, `aggregateType`, `aggregateId`, `activityId`, `requestId`, and a typed notification payload.
- Produces: `DomainEventCodec.encode(DomainEventEnvelope event): String` and `decode(String json): DomainEventEnvelope`; decoding selects the payload type from the exact versioned `eventType` and rejects unknown versions.

- [ ] **Step 1: Write failing event contract tests**

Add tests named `routesEveryCatalogEventToExactlyOneConfiguredTopic`, `usesOrganizationAndActivityAsMessageKey`, `serializesOnlyTheStableEnvelope`, `codecRestoresTheTypedPayloadFromEventType`, `publishManyDeduplicatesRecipientsAndCreatesOneOutboxRowPerUser`, and `rejectsUnknownOrUnversionedEventTypes`. Assert UTC timestamps, `.v1` event types, no entity fields, exact topic defaults, and one unique event ID per recipient.

- [ ] **Step 2: Run the tests and verify RED**

Run: `cd backend && .\mvnw.cmd -q -Dtest=TransactionalOutboxEventPublisherTest,EventTopicRouterTest test`

Expected: FAIL because the event contracts and publisher are absent.

- [ ] **Step 3: Implement the event API and Outbox publisher**

Use Jackson's configured `ObjectMapper`, `Clock`, `IdWorker` for row IDs, `UUID.randomUUID()` for event IDs, and MDC `requestId`. Insert `PENDING`, `retryCount=0`, and `nextRetryAt=occurredAt`. Keep notification events recipient-specific and insert every row in the caller's existing transaction. Non-notification domain facts use their typed payload and still receive one stable event ID.

- [ ] **Step 4: Verify focused and full GREEN**

Run the focused command from Step 2, then `cd backend && .\mvnw.cmd test`.

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/volunteerflow/infrastructure/event backend/src/test/java/com/volunteerflow/infrastructure/event
git commit -m "feat(stage5): add transactional event publisher"
```

### Task 3: Implement the leased Outbox relay and Kafka producer adapter

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/KafkaEventProperties.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/KafkaEventConfiguration.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/KafkaEventGateway.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/KafkaPublishReceipt.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/SpringKafkaEventGateway.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/OutboxClaimService.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/OutboxStateService.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/OutboxRelayService.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/OutboxRelayJob.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/OutboxRetentionJob.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/event/OutboxClaimServiceTest.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/event/OutboxRelayServiceTest.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/event/KafkaEventConfigurationTest.java`

**Interfaces:**
- Consumes: Task 1 mapper methods and Task 2 serialized Outbox rows.
- Produces: `KafkaEventGateway.publish(String topic, String key, String json): CompletableFuture<KafkaPublishReceipt>`.
- Produces: `OutboxClaimService.claimBatch(String workerId): List<OutboxEvent>` and `OutboxRelayService.publishBatch(String workerId): int`.
- Defaults: poll 1 second, batch 100, lease 60 seconds, send timeout 10 seconds, retry 5 seconds to 5 minutes with bounded jitter, `acks=all`, producer idempotence enabled.

- [ ] **Step 1: Write failing relay tests**

Add tests named `claimsOnlyDueRowsInABoundedBatch`, `publishesOutsideTheClaimTransaction`, `recordsPartitionAndOffsetForTheCurrentOwner`, `publishSuccessWithStateUpdateFailureLeavesRowReclaimable`, `retriesBrokerFailuresWithoutMarkingDead`, `marksPermanentContractFailureDead`, `reclaimsExpiredLease`, `staleWorkerCannotFinishReclaimedRow`, and `retentionDeletesOnlyAnIdBoundedPublishedBatch`.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `cd backend && .\mvnw.cmd -q -Dtest=OutboxClaimServiceTest,OutboxRelayServiceTest,KafkaEventConfigurationTest test`

Expected: FAIL because Spring Kafka and relay types are missing.

- [ ] **Step 3: Add Spring Kafka configuration and minimal relay implementation**

Add `spring-kafka`; bind all settings below `volunteerflow.events`; disable fail-fast topic checks; do not add Kafka to core readiness. Keep claim transactions in `OutboxClaimService`, state transitions in `OutboxStateService`, and network I/O in `OutboxRelayService` so Kafka sends never hold row locks.

- [ ] **Step 4: Verify focused and full GREEN**

Run the focused command from Step 2, then `cd backend && .\mvnw.cmd test`.

Expected: all tests pass and application-context tests start without a Kafka broker.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/main/java/com/volunteerflow/infrastructure/event backend/src/test/java/com/volunteerflow/infrastructure/event
git commit -m "feat(stage5): publish leased outbox events"
```

### Task 4: Add the idempotent notification consumer and DLT policy

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/notification/NotificationEventHandler.java`
- Create: `backend/src/main/java/com/volunteerflow/notification/KafkaNotificationListener.java`
- Create: `backend/src/main/java/com/volunteerflow/notification/NotificationConsumerConfiguration.java`
- Modify: `backend/src/main/java/com/volunteerflow/notification/NotificationService.java`
- Create: `backend/src/test/java/com/volunteerflow/notification/NotificationEventHandlerTest.java`
- Create: `backend/src/test/java/com/volunteerflow/notification/KafkaNotificationListenerTest.java`
- Create: `backend/src/test/java/com/volunteerflow/notification/NotificationConsumerConfigurationTest.java`

**Interfaces:**
- Consumes: Task 2 `DomainEventEnvelope` and `DomainEventCodec`; Task 1 `ConsumedEventMapper.insertIfAbsent`.
- Produces: `NotificationEventHandler.handle(DomainEventEnvelope event): ProcessingResult`, where the result is `CREATED`, `NO_NOTIFICATION`, or `DUPLICATE`.
- Produces: `KafkaNotificationListener.onMessage(ConsumerRecord<String, String> record): void` for all three topics under group `volunteerflow-notification-v1`.

- [ ] **Step 1: Write failing handler and listener tests**

Add tests named `commitsConsumedMarkerAndNotificationTogether`, `duplicateEventIsAcknowledgedWithoutSecondNotification`, `databaseFailureRollsBackConsumedMarker`, `unsupportedVersionDoesNotInsertConsumedMarker`, `malformedJsonRetriesThreeTimesThenPublishesToDlt`, and `dltHeadersContainOnlySafeOriginMetadata`.

- [ ] **Step 2: Run tests and verify RED**

Run: `cd backend && .\mvnw.cmd -q -Dtest=NotificationEventHandlerTest,KafkaNotificationListenerTest,NotificationConsumerConfigurationTest test`

Expected: FAIL because the consumer does not exist.

- [ ] **Step 3: Implement transactional handling and DLT configuration**

Use record acknowledgement after `NotificationEventHandler` returns. Configure two retries after the initial attempt and `DeadLetterPublishingRecoverer` to `<original-topic>.DLT`. A supported event with no notification intent records `consumed_event` and returns `NO_NOTIFICATION`. Keep `NotificationService` as the database notification sink used only by the consumer and notification-query controller; business services will stop injecting `NotificationWriter` in Tasks 5 and 6.

- [ ] **Step 4: Verify focused and full GREEN**

Run the focused command from Step 2, then `cd backend && .\mvnw.cmd test`.

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/volunteerflow/notification backend/src/test/java/com/volunteerflow/notification
git commit -m "feat(stage5): consume notification events idempotently"
```

### Task 5: Migrate registration and promotion notifications to Outbox events

**Files:**
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationSubmissionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationReviewService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationCancellationService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/PromotionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/PromotionExpiryService.java`
- Modify: `backend/src/test/java/com/volunteerflow/registration/RegistrationSubmissionServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/registration/RegistrationReviewServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/registration/RegistrationCancellationServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/registration/PromotionServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/registration/PromotionExpiryJobTest.java`
- Replace: `backend/src/test/java/com/volunteerflow/registration/CapturingNotificationWriter.java` with `CapturingDomainEventPublisher.java`

**Interfaces:**
- Consumes: Task 2 `DomainEventPublisher`.
- Produces registration event types: `registration.submitted.v1`, `registration.reviewed.v1`, `registration.cancelled.v1`, `registration.promotion-offered.v1`, `registration.promotion-accepted.v1`, `registration.promotion-declined.v1`, and `registration.promotion-expired.v1`.

- [ ] **Step 1: Change registration tests first**

For every existing notification assertion, assert the exact event type, recipient, aggregate ID, activity ID, notification text, and routing data instead. Add `registrationAndAuditRollBackWhenOutboxInsertFails` and `registrationCommitsWhileKafkaIsUnavailableBecauseNoKafkaCallOccursInTheTransaction`.

- [ ] **Step 2: Run the registration tests and verify RED**

Run: `cd backend && .\mvnw.cmd -q -Dtest=RegistrationSubmissionServiceTest,RegistrationReviewServiceTest,RegistrationCancellationServiceTest,PromotionServiceTest,PromotionExpiryJobTest test`

Expected: FAIL because services still call `NotificationWriter`.

- [ ] **Step 3: Replace synchronous registration notification writes**

Inject `DomainEventPublisher`, preserve the existing transaction and lock order, and publish the exact `DomainEventCommand` after business state and audit writes but before commit. Do not catch Outbox insert failures. Do not call `KafkaTemplate` or `KafkaEventGateway` from any registration service.

- [ ] **Step 4: Verify focused and full GREEN**

Run the focused command from Step 2, then `cd backend && .\mvnw.cmd test`.

Expected: all tests pass and no registration production class references `NotificationWriter`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/volunteerflow/registration backend/src/test/java/com/volunteerflow/registration
git commit -m "feat(stage5): emit registration outbox events"
```

### Task 6: Migrate activity and check-in notifications to Outbox events

**Files:**
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityService.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityChangeService.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinSessionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/checkin/CheckinService.java`
- Modify: `backend/src/test/java/com/volunteerflow/activity/ActivityServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/activity/ActivityChangeServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/CheckinSessionServiceTest.java`
- Modify: `backend/src/test/java/com/volunteerflow/checkin/CheckinServiceTest.java`

**Interfaces:**
- Consumes: Task 2 `DomainEventPublisher`.
- Produces activity event types: `activity.published.v1`, `activity.changed.v1`, and `activity.cancelled.v1`.
- Produces check-in event types: `checkin.opened.v1`, `checkin.closed.v1`, `checkin.completed.v1`, and `checkin.corrected.v1`.

- [ ] **Step 1: Change activity and check-in tests first**

Replace notification-capture assertions with event assertions. Pin one Outbox row per distinct recipient for fan-out, exact activity Kafka key, UTC occurrence time, and rollback with the business transaction. Add tests for manual correction and self check-in so `completed` and `corrected` cannot be silently omitted.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `cd backend && .\mvnw.cmd -q -Dtest=ActivityServiceTest,ActivityChangeServiceTest,CheckinSessionServiceTest,CheckinServiceTest test`

Expected: FAIL because these services still create notifications synchronously or do not publish the new events.

- [ ] **Step 3: Emit activity and check-in events**

Replace business-service `NotificationWriter` dependencies with `DomainEventPublisher`. Preserve existing recipient selection and user-visible text. Emit check-in completion and correction events without adding a second notification unless the current product behavior requires one; an event may carry a recipient-specific notification intent only when a notification should exist.

- [ ] **Step 4: Verify focused and full GREEN**

Run the focused command from Step 2, run `rg -n "NotificationWriter" backend/src/main/java/com/volunteerflow/activity backend/src/main/java/com/volunteerflow/registration backend/src/main/java/com/volunteerflow/checkin`, then run `cd backend && .\mvnw.cmd test`.

Expected: tests pass and the search finds no business-service dependency on `NotificationWriter`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/volunteerflow/activity backend/src/main/java/com/volunteerflow/checkin backend/src/test/java/com/volunteerflow/activity backend/src/test/java/com/volunteerflow/checkin
git commit -m "feat(stage5): emit activity and checkin events"
```

### Task 7: Add indexed promotion reconciliation

**Files:**
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionReconciliationProperties.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionReconciliationRequester.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionReconciliationService.java`
- Create: `backend/src/main/java/com/volunteerflow/registration/PromotionReconciliationJob.java`
- Modify: `backend/src/main/java/com/volunteerflow/VolunteerFlowApplication.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/RegistrationCancellationService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/PromotionService.java`
- Modify: `backend/src/main/java/com/volunteerflow/registration/PromotionExpiryService.java`
- Modify: `backend/src/main/java/com/volunteerflow/activity/ActivityChangeService.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/volunteerflow/registration/PromotionReconciliationRequesterTest.java`
- Create: `backend/src/test/java/com/volunteerflow/registration/PromotionReconciliationServiceTest.java`
- Create: `backend/src/test/java/com/volunteerflow/registration/PromotionReconciliationJobTest.java`

**Interfaces:**
- Consumes: Task 1 `PromotionReconcileTaskMapper`; existing `PromotionService.offerNextFirstCome(Long positionId, Long createdBy, String reason)`.
- Produces: `PromotionReconciliationRequester.request(Long organizationId, Long positionId, LocalDateTime nextRunAt): void`.
- Produces: `PromotionReconciliationService.reconcile(Long positionId, long claimedVersion): ReconciliationResult` and `PromotionReconciliationJob.reconcileBatch(): int`.
- Defaults: scan delay 30 seconds, batch 100, lease 60 seconds, retry 5 seconds to 5 minutes.

- [ ] **Step 1: Write failing reconciliation tests**

Add tests named `coalescesRepeatedRequestsByIncrementingVersion`, `claimsOnlyIndexedDueTasks`, `createsAtMostOneRequiredActiveOffer`, `secondRunIsIdempotent`, `newRequestDuringProcessingRemainsPending`, `staleWorkerCannotCompleteNewLease`, and `capacityIncreaseCancellationDeclineAndExpiryScheduleThePositionInTheSameTransaction`.

- [ ] **Step 2: Run tests and verify RED**

Run: `cd backend && .\mvnw.cmd -q -Dtest=PromotionReconciliationRequesterTest,PromotionReconciliationServiceTest,PromotionReconciliationJobTest test`

Expected: FAIL because the reconciliation components do not exist.

- [ ] **Step 3: Implement the requester, worker, and scheduling hooks**

Reuse the current member-position-cycle-offer lock order and `offerNextFirstCome`. Keep the immediate promotion path; schedule the safety-net row in the same transaction whenever capacity or a reservation is released. Process one claimed position per transaction so one failure cannot roll back the rest of the batch.

- [ ] **Step 4: Verify query shape, focused tests, and full GREEN**

Run the focused command from Step 2. Run the migration/SQL contract test to prove `status,next_run_at,position_id` bounded access. Then run `cd backend && .\mvnw.cmd test`.

Expected: all tests pass and no reconciliation query scans `activity` or all `activity_position` rows.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/volunteerflow/VolunteerFlowApplication.java backend/src/main/java/com/volunteerflow/registration backend/src/main/java/com/volunteerflow/activity/ActivityChangeService.java backend/src/main/resources/application.yml backend/src/test/java/com/volunteerflow/registration
git commit -m "feat(stage5): reconcile waitlist promotions"
```

### Task 8: Add metrics, component health, and Kafka/MySQL integration tests

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/EventPipelineMetrics.java`
- Create: `backend/src/main/java/com/volunteerflow/infrastructure/event/EventPipelineHealthIndicator.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/event/EventPipelineMetricsTest.java`
- Create: `backend/src/test/java/com/volunteerflow/infrastructure/event/EventPipelineHealthIndicatorTest.java`
- Create: `backend/src/test/java/com/volunteerflow/integration/Stage5KafkaOutboxIntegrationTest.java`
- Create: `backend/src/test/java/com/volunteerflow/integration/Stage5KafkaOutageIntegrationTest.java`

**Interfaces:**
- Consumes: relay, consumer, and reconciliation results from Tasks 3, 4, and 7.
- Produces the metric names from spec section 10 and component health `eventPipeline` without adding it to `management.endpoint.health.group.readiness.include`.

- [ ] **Step 1: Write failing metric and integration tests**

Assert pending/dead/oldest age gauges, publish success/failure, consumer success/duplicate/DLT, reconciliation pending/success/failure, and Kafka client lag exposure. Integration tests must prove normal publish-consume, duplicate delivery, Kafka stop/business success/pending Outbox, Kafka restart/one notification, and database rollback boundaries.

- [ ] **Step 2: Run tests and verify RED**

Run: `cd backend && .\mvnw.cmd -q -Dtest=EventPipelineMetricsTest,EventPipelineHealthIndicatorTest test`

Expected: FAIL because metrics and health do not exist. Run the integration test profile separately and record the expected missing Testcontainers dependencies.

- [ ] **Step 3: Implement metrics, health, and Testcontainers support**

Add Testcontainers JUnit, MySQL, and Kafka test dependencies. Keep integration tests out of the default unit profile only if Docker detection is explicit; CI/live acceptance must have a command that fails rather than silently skips when Docker is required. Restart the same Kafka test container through Docker and wait for broker readiness before asserting relay recovery.

- [ ] **Step 4: Verify focused, integration, and full GREEN**

Run: `cd backend && .\mvnw.cmd -q -Dtest=EventPipelineMetricsTest,EventPipelineHealthIndicatorTest test`

Run: `cd backend && .\mvnw.cmd -Pstage5-integration verify`

Run: `cd backend && .\mvnw.cmd test`

Expected: unit and integration suites pass; application startup without Kafka passes; readiness contains DB but not Kafka.

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/main/java/com/volunteerflow/infrastructure/event backend/src/test/java/com/volunteerflow/infrastructure/event backend/src/test/java/com/volunteerflow/integration
git commit -m "test(stage5): verify Kafka outage recovery"
```

### Task 9: Add KRaft deployment, acceptance tooling, and evidence documentation

**Files:**
- Create: `deploy/stage5/.env.example`
- Create: `deploy/stage5/docker-compose.yml`
- Create: `deploy/stage5/run-stage5.ps1`
- Create: `deploy/stage5/remote-stage5.sh`
- Create: `deploy/stage5/wait-for-stage5.sh`
- Create: `deploy/stage5/replay-dlt.sh`
- Create: `deploy/stage5/verify-stage5.sql`
- Create: `backend/src/test/java/com/volunteerflow/Stage5DeploymentContractTest.java`
- Create: `backend/src/test/java/com/volunteerflow/Stage5RunnerContractTest.java`
- Create: `docs/operations/stage-5-kafka-outbox.md`
- Modify: `README.md`
- Create after a passing live run: `docs/reports/2026-10-18-stage-5-kafka-outbox.md`
- Create after a passing live run: `backend/src/test/java/com/volunteerflow/Stage5DocumentationContractTest.java`

**Interfaces:**
- Consumes: application image and Stage 5 configuration from Tasks 3-8.
- Produces a run-scoped evidence directory containing environment, Compose config, readiness, Kafka stop/start, Outbox snapshots, database terminal state, offsets, metrics, secret scan, cleanup, and run status.

- [ ] **Step 1: Write failing deployment and runner contracts**

Assert single-node KRaft, internal-only Kafka, explicit JVM memory limit, two backends, existing external MySQL/Redis, `SPRING_FLYWAY_ENABLED=false` for the restored environment, runId-prefixed resources, Kafka-only outage injection, bounded readiness waits, evidence download, secret scan, cleanup that targets only run-scoped resources, and a DLT replay command that requires explicit topic, partition, start offset, and end offset.

- [ ] **Step 2: Run contracts and verify RED**

Run: `cd backend && .\mvnw.cmd -q -Dtest=Stage5DeploymentContractTest,Stage5RunnerContractTest test`

Expected: FAIL because `deploy/stage5` does not exist.

- [ ] **Step 3: Implement Compose, scripts, and operations guide**

Follow `deploy/stage4d` safety patterns. The runner must back up and fingerprint the restored database before offering the separately reviewed V10 SQL; never enable Flyway or apply the migration without the explicit live-run gate. Stop and restart only the run-scoped Kafka container. Capture exact Outbox, notification, `consumed_event`, reconciliation, offset, and metric evidence.

- [ ] **Step 4: Verify source contracts and full suite**

Run the focused command from Step 2, `docker compose -f deploy/stage5/docker-compose.yml config`, secret-pattern scans, `git diff --check`, and `cd backend && .\mvnw.cmd test`.

Expected: static deployment gates and the full backend suite pass. Do not mark Stage 5 complete yet.

- [ ] **Step 5: Commit deployment tooling**

```bash
git add deploy/stage5 docs/operations/stage-5-kafka-outbox.md README.md backend/src/test/java/com/volunteerflow/Stage5DeploymentContractTest.java backend/src/test/java/com/volunteerflow/Stage5RunnerContractTest.java
git commit -m "feat(stage5): add Kafka outage acceptance stack"
```

- [ ] **Step 6: Run live acceptance and publish only measured evidence**

Execute the runner against the approved VM. Require every run-status gate to pass: normal event, Kafka outage business success, pending Outbox, Kafka recovery, exactly one notification, duplicate suppression, promotion reconciliation idempotency, metrics, original-data fingerprint, run-scoped cleanup, evidence download, and temporary-key removal.

Write the report and documentation contract from the successful evidence. Include environment limitations and measured values; do not invent throughput, lag, or error rates.

- [ ] **Step 7: Verify documentation and commit the measured report**

Run: `cd backend && .\mvnw.cmd -q -Dtest=Stage5DocumentationContractTest test`

Run: `cd backend && .\mvnw.cmd test`

Run: `git diff --check`

Expected: documentation contract and full suite pass, with a clean secret scan.

```bash
git add README.md docs/reports/2026-10-18-stage-5-kafka-outbox.md backend/src/test/java/com/volunteerflow/Stage5DocumentationContractTest.java
git commit -m "docs(stage5): publish Kafka recovery evidence"
```

## Final branch verification

- [ ] Run `cd backend && .\mvnw.cmd test` and record the exact test count.
- [ ] Run `cd backend && .\mvnw.cmd -Pstage5-integration verify` on a Docker-capable host.
- [ ] Run Stage 5 deployment contracts and the live outage/recovery runner.
- [ ] Run `git diff --check`, a repository secret scan, and `git status --short --branch`.
- [ ] Request an independent whole-branch review against the design spec and this plan.
- [ ] Do not merge or push until the user selects the integration action.
