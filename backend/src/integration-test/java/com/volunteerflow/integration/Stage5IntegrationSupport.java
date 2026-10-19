package com.volunteerflow.integration;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.volunteerflow.activity.ActivityRegistrationPolicyService;
import com.volunteerflow.audit.AuditService;
import com.volunteerflow.infrastructure.event.*;
import com.volunteerflow.notification.*;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import com.volunteerflow.registration.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import stage5support.Stage5PipelineApplication;

/** Only disposable local containers. No production Application scanning, schedules, Redis, or local config import. */
@SpringBootTest(classes = Stage5PipelineApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
      "spring.config.import=", "spring.flyway.enabled=true",
      "volunteerflow.events.send-timeout=2s", "volunteerflow.events.retry-initial=100ms",
      "volunteerflow.events.retry-max=1s", "volunteerflow.events.consumer-auto-startup=false",
      "management.endpoint.health.show-details=always", "management.endpoint.health.show-components=always",
      "management.endpoint.health.group.dependencies.include=db",
      "logging.level.org.apache.kafka=ERROR", "logging.level.org.testcontainers=INFO"
    })
@ActiveProfiles("stage5-integration")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class Stage5IntegrationSupport {
  static {
    String endpoint = System.getenv("DOCKER_HOST");
    if (endpoint != null && !endpoint.isBlank() && !endpoint.startsWith("npipe:")
        && !endpoint.startsWith("unix:") && !endpoint.matches("tcp://(localhost|127\\.0\\.0\\.1|\\[::1\\]):[0-9]+")) {
      throw new IllegalStateException("STAGE5_INTEGRATION_REQUIRES_LOCAL_DOCKER: remote Docker endpoints are forbidden");
    }
    if (!DockerClientFactory.instance().isDockerAvailable()) {
      throw new IllegalStateException("STAGE5_INTEGRATION_REQUIRES_LOCAL_DOCKER: Docker is unavailable; no tests were skipped");
    }
    var transport = DockerClientFactory.instance().getTransportConfig().getDockerHost();
    if (!Set.of("npipe", "unix").contains(transport.getScheme())
        && !Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(transport.getHost())) {
      throw new IllegalStateException("STAGE5_INTEGRATION_REQUIRES_LOCAL_DOCKER: nonlocal Docker transport is forbidden");
    }
  }

  @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.43")
      .withDatabaseName("stage5_disposable").withUsername("stage5").withPassword(UUID.randomUUID().toString());
  @Container static final KafkaContainer broker = new KafkaContainer("apache/kafka:3.9.1");

  static final String REGISTRATION = "volunteerflow.registration-events.v1";
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired RegistrationSubmissionService submissions;
  @Autowired OutboxEventMapper outbox;
  @Autowired OutboxRelayService relay;
  @Autowired OutboxClaimService claims;
  @Autowired DomainEventCodec codec;
  @Autowired NotificationEventHandler handler;
  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired KafkaListenerEndpointRegistry listeners;
  @Autowired MeterRegistry meters;
  @Autowired EventPipelineHealthIndicator pipeline;
  @Autowired HealthEndpoint health;
  @Autowired PromotionReconcileTaskMapper tasks;
  @Autowired SqlSessionFactory sqlSessions;
  @Autowired PlatformTransactionManager transactions;

  @DynamicPropertySource
  static void containers(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", mysql::getJdbcUrl);
    registry.add("spring.datasource.username", mysql::getUsername);
    registry.add("spring.datasource.password", mysql::getPassword);
    registry.add("volunteerflow.events.bootstrap-servers", broker::getBootstrapServers);
  }

  @BeforeEach
  void fixtures() throws Exception {
    listeners.stop();
    for (String table : List.of("consumed_event", "notification", "outbox_event", "audit_log",
        "registration_answer", "promotion_offer", "registration_cycle", "registration", "promotion_reconcile_task",
        "activity_position", "activity", "organization_member")) jdbc.update("DELETE FROM " + table);
    jdbc.update("""
        INSERT INTO activity (id, organization_id, title, description, location, registration_start_at,
          registration_end_at, activity_start_at, activity_end_at, status, created_by)
        VALUES (3001,1001,'Task8 fixture','Task8 fixture','isolated',?, ?, ?, ?, 'PUBLISHED',101)
        """, LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(1),
        LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(3));
    jdbc.update("""
        INSERT INTO activity_position(id,organization_id,activity_id,name,description,capacity,registration_mode,status)
        VALUES (4001,1001,3001,'Task8 position','isolated',10,'FIRST_COME','ACTIVE')
        """);
    for (long user : List.of(101L, 102L, 103L)) jdbc.update("""
        INSERT INTO organization_member(id,organization_id,user_id,role_id,status,joined_at)
        VALUES (?,1001,?,1,'ACTIVE',CURRENT_TIMESTAMP(6))
        """, user, user);
    waitForBroker();
    try (var admin = admin()) {
      List<NewTopic> create = new ArrayList<>();
      Set<String> names = admin.listTopics().names().get(10, TimeUnit.SECONDS);
      for (String topic : List.of("volunteerflow.activity-events.v1", REGISTRATION, "volunteerflow.checkin-events.v1")) {
        for (String name : List.of(topic, topic + ".DLT")) if (!names.contains(name)) create.add(new NewTopic(name, 1, (short) 1));
      }
      if (!create.isEmpty()) admin.createTopics(create).all().get(15, TimeUnit.SECONDS);
    }
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE()
        """, Long.class)).isZero();
    assertCoreReadiness();
  }

  RegistrationSubmissionService.RegistrationResult submit(long user) {
    return submissions.submit(user, 3001L,
        new RegistrationSubmissionService.SubmitRegistrationRequest(4001L, List.of()));
  }

  String eventId(long registrationId) {
    return jdbc.queryForObject("SELECT event_id FROM outbox_event WHERE aggregate_id=?", String.class,
        Long.toString(registrationId));
  }

  long outboxId(String event) {
    return jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_id=?", Long.class, event);
  }

  long count(String table, String condition) {
    // Table/condition are fixed test-owned literals or generated UUIDs, never user input.
    return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + condition, Long.class);
  }

  double counter(String name) { return meters.get(name).counter().count(); }

  void drain(String event) {
    listeners.start();
    await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
      relay.publishBatch(UUID.randomUUID().toString());
      assertThat(count("outbox_event", "event_id='" + event + "' AND status='PUBLISHED'")).isEqualTo(1);
      assertThat(count("consumed_event", "event_id='" + event + "'")).isEqualTo(1);
    });
  }

  void waitForDuplicate(double before) {
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        assertThat(counter("kafka.consumer.duplicate")).isGreaterThan(before));
  }

  AdminClient admin() {
    return AdminClient.create(Map.of("bootstrap.servers", broker.getBootstrapServers(),
        "request.timeout.ms", 1000, "default.api.timeout.ms", 2000));
  }

  void waitForBroker() {
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
      try (var admin = admin()) {
        assertThat(admin.describeCluster().nodes().get(3, TimeUnit.SECONDS)).isNotEmpty();
      }
    });
  }

  void assertCoreReadiness() {
    // The production application.yml defines the readiness membership; do not probe Kafka here.
    var readiness = (CompositeHealth) health.healthForPath("readiness");
    assertThat(readiness.getComponents()).doesNotContainKeys("eventPipeline", "redis", "kafka");
    assertThat(readiness.getComponents().get("db").getStatus().getCode()).isEqualTo("UP");
    assertThat(readiness.getStatus().getCode()).isEqualTo("UP");
  }

  void assertDlt() throws Exception {
    listeners.start();
    var topic = new TopicPartition(REGISTRATION + ".DLT", 0);
    try (var consumer = new KafkaConsumer<String, String>(Map.of(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers(),
        ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
        ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
        ConsumerConfig.GROUP_ID_CONFIG, "task8-dlt-" + UUID.randomUUID(),
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
      consumer.assign(List.of(topic));
      consumer.seekToEnd(List.of(topic));
      consumer.position(topic);
      double before = counter("kafka.consumer.dlt");
      kafka.send(REGISTRATION, "1001:3001", "malformed-task8-event").get(10, TimeUnit.SECONDS);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        var records = consumer.poll(Duration.ofMillis(500));
        assertThat(records).anySatisfy(record -> {
          assertThat(record.value()).isEqualTo("malformed-task8-event");
          assertThat(record.headers()).extracting(h -> h.key()).containsExactlyInAnyOrder(
              "source-topic", "source-partition", "source-offset", "error-category");
        });
      });
      assertThat(counter("kafka.consumer.dlt")).isEqualTo(before + 1);
      assertThat(count("consumed_event", "1=1")).isZero();
      assertThat(count("notification", "1=1")).isZero();
    }
  }

  void assertIndexedPlansAndContention() throws Exception {
    String insert = """
        INSERT INTO outbox_event (id,event_id,aggregate_type,aggregate_id,event_type,payload_json,status,
          next_retry_at,published_at,topic,message_key,locked_by,locked_until)
        VALUES (?,?,'REGISTRATION','1','registration.submitted.v1','{}',?,?,?,?,?,'old',?)
        """;
    LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
    List<Object[]> rows = new ArrayList<>();
    for (long id = 1; id <= 4000; id++) {
      String status = id <= 2 ? "PENDING" : id <= 4 ? "PROCESSING" : id == 5 ? "PUBLISHED" : "RETRY";
      rows.add(new Object[]{id, UUID.randomUUID().toString(), status,
          id <= 2 ? now.minusMinutes(1) : now.plusDays(5), id == 5 ? now.minusDays(10) : null,
          REGISTRATION, "1001:3001", now.minusMinutes(1)});
    }
    jdbc.batchUpdate(insert, rows);
    for (long id = 1; id <= 4; id++) {
      tasks.upsertRequest(1001L, id, now.minusMinutes(1));
      if (id > 2) jdbc.update("UPDATE promotion_reconcile_task SET status='PROCESSING',locked_by='old',locked_until=? WHERE position_id=?", now.minusMinutes(1), id);
    }
    jdbc.execute("ANALYZE TABLE outbox_event");
    jdbc.execute("ANALYZE TABLE promotion_reconcile_task");
    Map<String,Object> due = Map.of("now", now, "limit", 2);
    explain(OutboxEventMapper.class, "selectDueForUpdate", due, "idx_outbox_event_status_retry_id");
    explain(OutboxEventMapper.class, "selectExpiredLeasesForUpdate", due, "idx_outbox_event_status_lease_id");
    explain(PromotionReconcileTaskMapper.class, "selectDueForUpdate", due, "idx_promotion_reconcile_status_run_position");
    explain(PromotionReconcileTaskMapper.class, "selectExpiredLeasesForUpdate", due, "idx_promotion_reconcile_status_lease_position");
    Map<String,Object> retention = new HashMap<>();
    retention.put("cutoff", now.minusDays(7)); retention.put("cursorTime", now.minusDays(11));
    retention.put("cursorId", 0L); retention.put("limit", 2);
    explain(OutboxEventMapper.class, "selectPublishedIdsBefore", retention, "idx_outbox_event_published_at_id");
    assertThat(outbox.selectPublishedIdsBefore(now.minusDays(7), now.minusDays(11), 0L, 2)).containsExactly(5L);
    assertSkipLocked("outbox_event", "id", () -> claims.claimBatch("second-owner").stream().map(OutboxEvent::getId).toList());
    assertSkipLocked("promotion_reconcile_task", "position_id", () -> new TransactionTemplate(transactions).execute(s -> {
      var selected = tasks.selectClaimableForUpdate(now, 2);
      for (var task : selected) tasks.claim(task.getPositionId(), task.getRequestVersion(), "second-owner", now.plusMinutes(1), now);
      return selected.stream().map(PromotionReconcileTask::getPositionId).toList();
    }));
    assertThat(outbox.markPublished(2L, "stale-owner", 0, 1, now)).isZero();
    assertThat(outbox.markPublished(2L, "second-owner", 0, 1, now)).isEqualTo(1);
    assertThat(outbox.markPublished(3L, "old", 0, 1, now)).isZero();
    assertThat(outbox.selectById(3L).getLockedBy()).isEqualTo("second-owner");
    tasks.upsertRequest(1001L, 2L, now);
    assertThat(tasks.markProcessed(2L, 1, "second-owner", now)).isEqualTo(1);
    assertThat(jdbc.queryForMap("SELECT status,request_version,processed_version FROM promotion_reconcile_task WHERE position_id=2"))
        .containsEntry("status", "PENDING").containsEntry("request_version", 2L).containsEntry("processed_version", 1L);
    List<PromotionReconcileTask> expired = new TransactionTemplate(transactions)
        .execute(s -> tasks.selectExpiredLeasesForUpdate(now, 2));
    assertThat(expired)
        .extracting(PromotionReconcileTask::getPositionId).containsExactly(4L);
    assertThat(tasks.markProcessed(3L, 1, "old", now)).isZero();
    assertThat(tasks.markProcessed(3L, 1, "second-owner", now)).isEqualTo(1);
  }

  void explain(Class<?> mapper, String method, Map<String,Object> parameters, String expectedIndex) {
    var bound = sqlSessions.getConfiguration().getMappedStatement(mapper.getName() + "." + method).getBoundSql(parameters);
    Object[] args = bound.getParameterMappings().stream().map(p -> parameters.get(p.getProperty())).toArray();
    var plan = jdbc.queryForMap("EXPLAIN " + bound.getSql(), args);
    assertThat(plan).containsEntry("key", expectedIndex);
    assertThat(plan.get("type")).isNotEqualTo("ALL");
    assertThat(String.valueOf(plan.get("Extra"))).doesNotContainIgnoringCase("filesort");
    System.out.println("STAGE5_EXPLAIN " + method + " key=" + plan.get("key") + " type=" + plan.get("type") + " extra=" + plan.get("Extra"));
  }

  void assertSkipLocked(String table, String key, Callable<List<Long>> claim) throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try (Connection lock = dataSource.getConnection()) {
      lock.setAutoCommit(false);
      try (var statement = lock.prepareStatement("SELECT " + key + " FROM " + table + " WHERE " + key + "=1 FOR UPDATE")) {
        statement.executeQuery().close();
        assertThat(worker.submit(claim).get(3, TimeUnit.SECONDS)).doesNotContain(1L).contains(2L);
      } finally { lock.rollback(); }
    } finally { worker.shutdownNow(); }
  }

}
