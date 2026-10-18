# VolunteerFlow Stage 5 Kafka and Transactional Outbox Design

- Date: 2026-10-18
- Status: approved design, pending implementation plan
- Baseline: Java 17, Spring Boot 3.5, MyBatis-Plus, MySQL 8, Redis, Kafka KRaft
- Branch: `feat/stage-5`

## 1. Goal and scope

Stage 5 replaces synchronous creation of in-app notifications with a reliable asynchronous
pipeline. A business transaction writes its domain data, audit record, and an Outbox event to
MySQL atomically. A relay publishes the event to Kafka, and an idempotent consumer creates the
database notification.

The stage must prove all of the following:

- Kafka is not part of the registration, cancellation, review, promotion, or check-in
  transaction.
- A business transaction succeeds while Kafka is unavailable and leaves a publishable Outbox
  record.
- Events are published after Kafka recovers.
- Duplicate publication or delivery does not create duplicate notifications.
- Activity, registration, and check-in events have explicit, versioned contracts.
- Waitlist promotion has a database-driven reconciliation safety net that does not scan every
  activity or position.
- Kafka backlog, Outbox backlog, duplicate consumption, dead-lettering, and reconciliation work
  are observable.

This stage does not introduce microservices, Kafka Streams, Schema Registry, Debezium, chat, AI,
Prometheus dashboards, or production high availability. Existing notification query, unread-count,
and mark-read APIs remain unchanged.

## 2. Broker decision

VolunteerFlow uses Kafka rather than RabbitMQ for Stage 5. RabbitMQ would be a good fit for a
notification-only work queue because it has first-class acknowledgements, routing, TTL, and
dead-letter exchanges. Kafka fits this project better because the approved roadmap needs retained
domain events, offset-based replay, independent notification and future statistics consumers,
consumer-lag evidence, and later chat or AI event consumers.

The decision is based on project fit rather than a claim that Kafka is universally faster or more
reliable. Delivery is still designed as at least once. Kafka transactions do not make MySQL side
effects exactly once; database uniqueness and consumer idempotency remain mandatory.

## 3. Architecture and transaction boundaries

```text
activity / registration / check-in transaction
        |
        +-- update business state
        +-- append audit record
        +-- insert outbox_event
                | same MySQL transaction
                v
          Outbox relay
                |
       +--------+---------+
       v        v         v
   activity  registration check-in
    topic       topic      topic
       +--------+---------+
                v
      notification consumer
                |
       consumed_event + notification
             one MySQL transaction
```

MySQL remains the system of record. Kafka unavailability must not roll back business data. Audit
records remain synchronous and are never delegated to Kafka. The notification database table
remains the final source for notification queries.

The relay may publish an event and crash before marking the Outbox row as published. The same
event may therefore be sent again. Consumers must treat duplicates as normal input.

## 4. Topics, keys, and event envelope

Topic names are external configuration values with these defaults:

```text
volunteerflow.activity-events.v1
volunteerflow.registration-events.v1
volunteerflow.checkin-events.v1
```

All events use a stable JSON envelope:

```json
{
  "eventId": "b1445338-156d-41e3-a131-8f349282125d",
  "eventType": "registration.confirmed.v1",
  "occurredAt": "2026-10-18T14:30:00Z",
  "organizationId": 1001,
  "aggregateType": "REGISTRATION",
  "aggregateId": "2001",
  "activityId": 3001,
  "requestId": "request correlation identifier",
  "payload": {}
}
```

Rules:

- `eventId` is a UUID and is globally unique.
- `eventType` includes its schema version and never depends on a Java class name.
- `occurredAt` is UTC.
- Payloads contain only fields required by consumers, not serialized persistence entities.
- Passwords, tokens, full contact details, and other secrets are forbidden in events and logs.
- Events related to one activity use `organizationId:activityId` as their Kafka key. This keeps
  one activity's events ordered within a partition while allowing different activities to run in
  parallel.
- Unknown versions are not guessed or partially processed. They follow the consumer failure and
  dead-letter policy.

The initial event catalog is:

```text
activity.published.v1
activity.changed.v1
activity.cancelled.v1

registration.submitted.v1
registration.reviewed.v1
registration.cancelled.v1
registration.promotion-offered.v1
registration.promotion-accepted.v1
registration.promotion-declined.v1
registration.promotion-expired.v1

checkin.opened.v1
checkin.closed.v1
checkin.completed.v1
checkin.corrected.v1
```

The event payloads contain enough immutable display data for the notification consumer to build
the same user-visible notification that the synchronous writer creates today. Consumers may query
current business state for authorization or existence checks, but must not rely on a later mutable
title or status to reconstruct the historical message.

## 5. Database design

All changes use new Flyway migrations. No physical foreign keys are added.

### 5.1 Extend `outbox_event`

The existing table receives:

```text
topic                 VARCHAR(160) NOT NULL
message_key           VARCHAR(160) NOT NULL
locked_by             VARCHAR(128) NULL
locked_until          DATETIME(6) NULL
last_error            VARCHAR(1000) NULL
published_partition   INT NULL
published_offset      BIGINT NULL
```

Supported states are:

```text
PENDING -> PROCESSING -> PUBLISHED
              |
              +-------> RETRY -> PROCESSING

permanent contract/configuration error -> DEAD
expired PROCESSING lease -> claimable again
```

The due-work index starts with `status`, then `next_retry_at`, then `id`. Published-row retention
uses a separate `published_at, id` index. Errors are sanitized and truncated before persistence.

Broker connection failures remain `RETRY` and are not converted to `DEAD`. `DEAD` is reserved for
permanent failures such as invalid stored JSON, unsupported event contracts, or invalid routing
configuration.

### 5.2 Add `consumed_event`

```text
id              BIGINT primary key
consumer_name   VARCHAR(128) not null
event_id        CHAR(36) not null
processed_at    DATETIME(6) not null
```

`(consumer_name, event_id)` is unique. The consumer inserts this row and all derived notifications
in one MySQL transaction. A duplicate-key result means the event was already processed and is an
idempotent success.

Notification deduplication keys are derived from `eventId`, recipient user ID, and `IN_APP`. The
existing notification unique constraint remains an additional guard.

### 5.3 Add `promotion_reconcile_task`

There is one durable row per position:

```text
position_id        BIGINT primary key
organization_id    BIGINT not null
status             VARCHAR(32) not null
request_version    BIGINT not null
processed_version  BIGINT not null
next_run_at        DATETIME(6) not null
locked_by          VARCHAR(128) null
locked_until       DATETIME(6) null
attempt_count      INT not null
last_error         VARCHAR(1000) null
create_time        DATETIME(6) not null
update_time        DATETIME(6) not null
```

The due-work index starts with `status`, then `next_run_at`, then `position_id`. Business actions
that can release capacity upsert this row and increment `request_version` in the same transaction.
The worker records the version it claimed. If `request_version` increased during processing, the
row remains pending instead of losing the newer request.

## 6. Outbox relay

The relay scans only indexed due work, ordered by ID, with a configured batch limit. Defaults are:

```text
poll interval       1 second
batch size          100
processing lease    60 seconds
Kafka send timeout  10 seconds
initial retry       5 seconds
maximum retry       5 minutes
```

Multiple application instances can run the relay:

1. A short MySQL transaction selects due rows with a bounded limit and locking that skips rows
   already held by another instance.
2. The transaction changes them to `PROCESSING`, sets `locked_by` and `locked_until`, and commits.
3. Kafka sends happen outside the database transaction.
4. Each successful send records `PUBLISHED`, `published_at`, partition, and offset.
5. Each retryable failure records `RETRY`, increments `retry_count`, and calculates exponential
   backoff with bounded jitter.
6. An expired processing lease makes a row eligible for reclamation.

The producer uses `acks=all` and Kafka producer idempotence. These settings improve broker-side
delivery safety but do not replace the consumer's database idempotency.

Published rows are purged only after the configured retention period, using `published_at, id`
keyset batches. There is no unbounded delete and no full-table maintenance scan.

## 7. Notification consumer and dead letters

The notification consumer group is `volunteerflow-notification-v1`. It uses record-level handling:

1. Parse and validate the envelope and supported version.
2. Start a MySQL transaction.
3. Insert `consumed_event`.
4. Map the domain event to one or more notification rows.
5. Commit MySQL.
6. Only then allow the Kafka offset to be acknowledged.

One delivery gets at most three immediate attempts. Blocking retry is used to retain ordering in an
activity partition. An exhausted record is published to `<original-topic>.DLT` with the original
event and safe origin metadata: source topic, partition, offset, and error category. Full stack
traces and sensitive payload data are not copied to headers or logs.

DLT records are not automatically looped back. A documented, operator-triggered replay accepts an
explicit topic and offset range after the underlying issue is fixed. Replay uses the original
`eventId`, so database idempotency still applies.

## 8. Promotion reconciliation

The normal business flow continues to promote a waitlisted member immediately when possible. The
reconciliation worker is a safety net for crashes, transient failures, and interrupted work; it is
independent of Kafka.

The following transitions schedule the affected position in the same database transaction:

- confirmed registration cancellation;
- position capacity increase;
- promotion offer decline;
- promotion offer expiry;
- any other transition that releases a confirmed or reserved place.

The worker claims only indexed, due task rows in fixed batches. For each position it reuses the
existing locking order and idempotent promotion rules to inspect capacity, effective offers, and
the first eligible waitlisted member. It creates at most one required active offer per available
place according to the approved business rules. Re-running the same task must not create a second
active offer.

The worker marks the claimed version processed only after reconciliation commits. If a newer
request version exists, it returns the row to pending. Retry uses bounded exponential backoff and a
lease so another instance can recover abandoned work.

## 9. Startup, health, and configuration

Kafka configuration is fully externalized. At minimum it includes bootstrap servers, client ID,
consumer group, topic names, batch sizes, leases, timeouts, retry bounds, DLT behavior, and
retention periods.

Kafka being unavailable must not stop Spring Boot from starting. Kafka is excluded from the core
readiness decision because registration and other MySQL-backed operations remain available. A
separate component status and metrics expose the degraded event pipeline without misrepresenting
the whole API as unavailable.

The demo deployment adds one single-node Kafka KRaft container with an explicit JVM memory limit.
It is an educational and acceptance environment, not a production highly available cluster.
Kafka is not exposed publicly. Compose owns only Stage 5 application resources and must not stop,
recreate, or replace the existing MySQL and Redis containers.

Fresh databases and automated integration tests use Flyway. The restored demonstration database
continues to run with `SPRING_FLYWAY_ENABLED=false`. Before that environment receives Stage 5,
operators must back it up, validate its current fingerprint and schema, review the exact additive
SQL, apply only the Stage 5 migration manually, and validate the before/after result. Existing
Flyway history must not be edited to conceal drift.

## 10. Observability

Stage 5 adds Actuator and Micrometer measurements; Stage 6 will add Prometheus and Grafana views.
Required measurements are:

```text
outbox.pending.count
outbox.dead.count
outbox.oldest.pending.age
outbox.publish.success
outbox.publish.failure

kafka.consumer.success
kafka.consumer.duplicate
kafka.consumer.dlt
Kafka client consumer lag

promotion.reconcile.pending
promotion.reconcile.success
promotion.reconcile.failure
```

Structured logs correlate `requestId`, `eventId`, topic, partition, and offset. Logs do not contain
full event payloads, credentials, tokens, or full contact details.

## 11. Testing strategy

Unit tests cover:

- event envelope validation and event-to-notification mapping;
- topic and key selection;
- relay status changes, leases, retry backoff, and permanent failure classification;
- duplicate consumer handling;
- promotion reconciliation version changes and retry behavior.

MySQL integration tests prove:

- business data, audit, and Outbox commit together;
- all three roll back together on a transaction failure;
- consumer idempotency and notification creation commit together;
- duplicate event IDs do not create duplicate notifications;
- due-work and retention queries use bounded indexed access;
- reconciliation does not lose a request created during processing.

Kafka/Testcontainers integration tests prove normal publication, consumption, duplicate delivery,
consumer retry, DLT routing, and recovery after a broker outage. Tests must assert database final
state, not only successful API or broker calls.

## 12. Acceptance procedure

The Stage 5 acceptance run must produce machine-readable evidence for this sequence:

1. Start MySQL, Redis, Kafka KRaft, and two backend instances.
2. Complete a normal registration; assert one Outbox event reaches `PUBLISHED` and one notification
   exists.
3. Stop only the Stage 5 Kafka container.
4. Complete another registration; assert business data and audit commit, the API succeeds, and the
   Outbox event remains pending or retryable.
5. Restart Kafka and assert that event is published and creates exactly one notification.
6. Deliver the same `eventId` again and assert notification count remains one while the duplicate
   metric increases.
7. Create an isolated position with an available place and a registered reconciliation task.
8. Run reconciliation and assert exactly one valid promotion offer is created.
9. Run reconciliation again and assert no second active offer is created.
10. Assert Outbox backlog, consumer lag, duplicate, failure, DLT, and reconciliation metrics are
    exposed with expected values.
11. Remove only run-scoped fixtures, application containers, Kafka data, and networks. Verify the
    original MySQL and Redis containers and pre-existing data are unchanged.

Kafka offset, Outbox status, database state, metric samples, cleanup results, and test output are
the acceptance evidence. Container startup by itself is not evidence of completion.

## 13. Delivery sequence

Implementation should proceed in these slices:

1. Database migrations and persistence models.
2. Event envelope, catalog, serialization, and transaction-local Outbox writer.
3. Relay claiming, publishing, retry, lease recovery, and retention.
4. Idempotent notification consumer, event mappings, retry, and DLT.
5. Migrate every existing synchronous notification producer to domain events.
6. Promotion reconciliation task and indexed worker.
7. Metrics, configuration, Kafka KRaft Compose, integration tests, and outage acceptance tooling.
8. Documentation and measured Stage 5 report.

Each slice follows test-driven development. A slice is complete only after focused tests and the
full backend suite pass. Live deployment claims require a separate evidence-producing run.
