package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.volunteerflow.activity.ActivityService;
import com.volunteerflow.notification.NotificationEventHandler;
import com.volunteerflow.organization.OrganizationService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in real MySQL tests on an exact isolated clone. No broker is started or contacted. */
@SpringBootTest(properties = "volunteerflow.rate-limit.enabled=false")
@ActiveProfiles("acceptance")
class OfflineOutboxMysqlIT {
  @Autowired JdbcTemplate sql;
  @Autowired ActivityService activities;
  @Autowired OrganizationService organizations;
  @Autowired DomainEventPublisher publisher;
  @Autowired OutboxRelayService relay;
  @Autowired DomainEventCodec codec;
  @Autowired NotificationEventHandler handler;
  @Autowired PlatformTransactionManager transactions;
  @MockitoBean KafkaEventGateway gateway;

  @Value("${volunteerflow.acceptance.database}")
  String database;

  Long actor, organizationId, activityId;
  String trigger;

  @BeforeEach
  void isolatedDraft() {
    assertThat(database).matches("vf_acceptance_[0-9a-f]{32}");
    assertThat(sql.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(database);
    // The recovered clone already has an administrator; never change its account or password.
    actor =
        sql.queryForObject(
            "SELECT MIN(id) FROM app_user WHERE platform_role='PLATFORM_ADMIN' AND status='ACTIVE'",
            Long.class);
    assertThat(actor).isNotNull();
    String suffix = UUID.randomUUID().toString();
    organizationId =
        organizations
            .create(
                actor,
                "PLATFORM_ADMIN",
                new OrganizationService.CreateOrganizationRequest(
                    "offline-" + suffix, "Isolated reliability fixture", actor))
            .getId();
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    activityId =
        activities
            .create(
                actor,
                organizationId,
                new ActivityService.ActivityRequest(
                    "offline-" + suffix,
                    "Reliability proof",
                    "Acceptance only",
                    now.minusHours(1),
                    now.plusHours(1),
                    now.plusHours(2),
                    now.plusHours(3),
                    now.plusHours(4)))
            .getId();
    activities.addPosition(
        actor,
        activityId,
        new ActivityService.PositionRequest(
            "test-position", "Acceptance only", 1, "FIRST_COME", 120, null, null, null));
  }

  @AfterEach
  void exactCleanup() {
    dropTrigger();
    if (organizationId == null) return;
    sql.update(
        "DELETE FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event WHERE"
            + " organization_id=?)",
        organizationId);
    for (String table :
        List.of(
            "promotion_reconcile_task",
            "notification",
            "outbox_event",
            "activity_position",
            "activity",
            "organization_invite",
            "organization_member",
            "rbac_role_permission",
            "rbac_role",
            "audit_log"))
      sql.update("DELETE FROM " + table + " WHERE organization_id=?", organizationId);
    sql.update("DELETE FROM organization WHERE id=?", organizationId);
  }

  @Test
  void outboxInsertFailureRollsBackBusinessAndAuditThenSucceedsOnRetry() {
    failInserts("outbox_event");
    assertThatThrownBy(() -> activities.publish(actor, activityId))
        .isInstanceOf(RuntimeException.class);
    assertThat(status()).isEqualTo("DRAFT");
    assertThat(count("outbox_event")).isZero();
    assertThat(
            sql.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE organization_id=? AND"
                    + " action='activity.published'",
                Long.class,
                organizationId))
        .isZero();
    dropTrigger();
    activities.publish(actor, activityId);
    assertThat(status()).isEqualTo("PUBLISHED");
    assertThat(count("outbox_event")).isEqualTo(1);
    verifyNoInteractions(gateway);
  }

  @Test
  void brokerFailureRetainsEventsAndDirectConsumerReplayIsIdempotent() {
    activities.publish(actor, activityId);
    UUID eventId = notificationEvent();
    verifyNoInteractions(gateway);
    when(gateway.publish(anyString(), anyString(), anyString()))
        .thenAnswer(
            call -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return CompletableFuture.failedFuture(
                  new IllegalStateException("OFFLINE_BROKER_FAILURE"));
            });
    assertThat(relay.publishBatch("offline-failure")).isZero();
    assertThat(status()).isEqualTo("PUBLISHED");
    assertThat(
            sql.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE organization_id=? AND status='RETRY' AND"
                    + " retry_count=1 AND last_error='BROKER_PUBLISH_FAILED' AND"
                    + " next_retry_at>UTC_TIMESTAMP(6)",
                Long.class,
                organizationId))
        .isEqualTo(2);
    // A synthetic acknowledgement verifies state handling only, not real Kafka delivery.
    when(gateway.publish(anyString(), anyString(), anyString()))
        .thenReturn(CompletableFuture.completedFuture(new KafkaPublishReceipt(0, 1)));
    sql.update(
        "UPDATE outbox_event SET next_retry_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE"
            + " organization_id=?",
        organizationId);
    assertThat(relay.publishBatch("offline-recovery")).isEqualTo(2);
    DomainEventEnvelope event = event(eventId);
    assertThat(handler.handle(event)).isEqualTo(NotificationEventHandler.ProcessingResult.CREATED);
    assertThat(handler.handle(event))
        .isEqualTo(NotificationEventHandler.ProcessingResult.DUPLICATE);
    assertThat(count("notification")).isEqualTo(1);
    assertThat(
            sql.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE event_id=?",
                Long.class,
                eventId.toString()))
        .isEqualTo(1);
  }

  @Test
  void notificationFailureRollsBackConsumptionMarkerAndCanBeRetried() {
    UUID eventId = notificationEvent();
    DomainEventEnvelope event = event(eventId);
    failInserts("notification");
    assertThatThrownBy(() -> handler.handle(event)).isInstanceOf(RuntimeException.class);
    assertThat(count("notification")).isZero();
    assertThat(
            sql.queryForObject(
                "SELECT COUNT(*) FROM consumed_event WHERE event_id=?",
                Long.class,
                eventId.toString()))
        .isZero();
    dropTrigger();
    assertThat(handler.handle(event)).isEqualTo(NotificationEventHandler.ProcessingResult.CREATED);
    assertThat(handler.handle(event))
        .isEqualTo(NotificationEventHandler.ProcessingResult.DUPLICATE);
    assertThat(count("notification")).isEqualTo(1);
  }

  UUID notificationEvent() {
    return new TransactionTemplate(transactions)
        .execute(
            ignored ->
                publisher.publish(
                    new DomainEventCommand(
                        organizationId,
                        activityId,
                        "ACTIVITY",
                        activityId.toString(),
                        DomainEventTypes.REGISTRATION_REVIEWED,
                        new NotificationIntentPayload(
                            actor,
                            "REGISTRATION_REVIEWED",
                            "离线可靠性验证",
                            "Test fixture only",
                            "ACTIVITY",
                            activityId))));
  }

  DomainEventEnvelope event(UUID id) {
    return codec.decode(
        sql.queryForObject(
            "SELECT payload_json FROM outbox_event WHERE event_id=? AND organization_id=?",
            String.class,
            id.toString(),
            organizationId));
  }

  String status() {
    return sql.queryForObject("SELECT status FROM activity WHERE id=?", String.class, activityId);
  }

  long count(String table) {
    return sql.queryForObject(
        "SELECT COUNT(*) FROM " + table + " WHERE organization_id=?", Long.class, organizationId);
  }

  void failInserts(String table) {
    trigger = "vf_offline_" + UUID.randomUUID().toString().replace("-", "");
    sql.execute(
        "CREATE TRIGGER `"
            + trigger
            + "` BEFORE INSERT ON `"
            + table
            + "` FOR EACH ROW BEGIN IF NEW.organization_id="
            + organizationId
            + " THEN SIGNAL SQLSTATE '45000' SET"
            + " MESSAGE_TEXT='OFFLINE_ACCEPTANCE_INJECTED_FAILURE'; END IF; END");
  }

  void dropTrigger() {
    if (trigger != null) {
      sql.execute("DROP TRIGGER IF EXISTS `" + trigger + "`");
      trigger = null;
    }
  }
}
