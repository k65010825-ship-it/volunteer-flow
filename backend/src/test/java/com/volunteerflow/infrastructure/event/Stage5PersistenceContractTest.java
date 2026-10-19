package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.Test;

/** SQL/binding contracts only; MySQL execution and EXPLAIN belong to integration tests. */
class Stage5PersistenceContractTest {
  private static final String OUTBOX = "com.volunteerflow.infrastructure.event.OutboxEventMapper";
  private static final String CONSUMED = "com.volunteerflow.infrastructure.event.ConsumedEventMapper";
  private static final String RECONCILE = "com.volunteerflow.registration.PromotionReconcileTaskMapper";

  @Test
  void outboxUpdatesRequireTheCurrentLeaseOwner() {
    for (String method : List.of("markPublished", "markRetry", "markDead")) {
      assertThat(sql(bound(OUTBOX, method, parameters())))
          .contains("WHERE id=? AND status='PROCESSING' AND locked_by=?",
              "locked_by=NULL", "locked_until=NULL");
    }
    BoundSql published = bound(OUTBOX, "markPublished", parameters());
    assertThat(sql(published)).contains("status='PUBLISHED'", "published_partition=?",
        "published_offset=?", "published_at=?", "last_error=NULL");
    assertThat(bindings(published)).containsExactly("partition", "offset", "publishedAt", "id", "workerId");
    assertThat(sql(bound(OUTBOX, "markRetry", parameters())))
        .contains("status='RETRY'", "retry_count=?", "next_retry_at=?", "last_error=?");
    assertThat(sql(bound(OUTBOX, "markDead", parameters()))).contains("status='DEAD'", "last_error=?");
  }

  @Test
  void consumedEventInsertIsIdempotent() {
    BoundSql statement = bound(CONSUMED, "insertIfAbsent", parameters());
    assertThat(sql(statement)).isEqualTo("INSERT IGNORE INTO consumed_event "
        + "(id, consumer_name, event_id, processed_at) VALUES (?, ?, ?, ?)");
    assertThat(bindings(statement)).containsExactly("id", "consumerName", "eventId", "processedAt");
  }

  @Test
  void outboxDiscoveryUsesBoundedDueAndExpiredLeaseQueries() {
    BoundSql statement = bound(OUTBOX, "selectDueForUpdate", parameters());
    assertThat(sql(statement)).isEqualTo("SELECT * FROM outbox_event "
        + "FORCE INDEX (idx_outbox_event_status_retry_id) "
        + "WHERE status IN ('PENDING', 'RETRY') AND (next_retry_at IS NULL OR next_retry_at<=?) "
        + "ORDER BY status, next_retry_at, id LIMIT ? FOR UPDATE SKIP LOCKED");
    assertThat(bindings(statement)).containsExactly("now", "limit");
    BoundSql expired = bound(OUTBOX, "selectExpiredLeasesForUpdate", parameters());
    assertThat(sql(expired)).isEqualTo("SELECT * FROM outbox_event "
        + "FORCE INDEX (idx_outbox_event_status_lease_id) "
        + "WHERE status='PROCESSING' AND locked_until<=? "
        + "ORDER BY locked_until, id LIMIT ? FOR UPDATE SKIP LOCKED");
    assertThat(bindings(expired)).containsExactly("now", "limit");
    assertThat(sql(bound(OUTBOX, "claim", parameters())))
        .contains("status='PROCESSING'", "locked_by=?", "locked_until=?",
            "WHERE id IN ( ? , ? )", "next_retry_at<=?", "locked_until<=?");
    Map<String, Object> empty = parameters();
    empty.put("ids", List.of());
    assertThat(sql(bound(OUTBOX, "claim", empty))).contains("WHERE 1=0");
    empty.put("ids", null);
    assertThat(sql(bound(OUTBOX, "claim", empty))).contains("WHERE 1=0");
  }

  @Test
  void retentionUsesPublishedKeysetAndExplicitIdsOnly() {
    BoundSql statement = bound(OUTBOX, "selectPublishedIdsBefore", parameters());
    assertThat(sql(statement)).contains("status='PUBLISHED'", "published_at<?",
        "published_at>? OR (published_at=? AND id>?)", "ORDER BY published_at, id LIMIT ?")
        .doesNotContainIgnoringCase("OFFSET");
    assertThat(bindings(statement)).containsExactly("cutoff", "cursorTime", "cursorTime", "cursorId", "limit");
    Map<String, Object> firstPage = parameters();
    firstPage.put("cursorTime", null);
    firstPage.put("cursorId", null);
    assertThat(bindings(bound(OUTBOX, "selectPublishedIdsBefore", firstPage)))
        .containsExactly("cutoff", "limit");
    assertThat(sql(bound(OUTBOX, "deletePublishedByIds", parameters())))
        .isEqualTo("DELETE FROM outbox_event WHERE status='PUBLISHED' AND id IN ( ? , ? )");
    firstPage.put("ids", List.of());
    assertThat(sql(bound(OUTBOX, "deletePublishedByIds", firstPage))).contains("AND 1=0");
    firstPage.put("ids", null);
    assertThat(sql(bound(OUTBOX, "deletePublishedByIds", firstPage))).contains("AND 1=0");
  }

  @Test
  void reconciliationClaimsDueWorkWithExpectedVersionAndOwnerFencing() {
    BoundSql due = bound(RECONCILE, "selectDueForUpdate", parameters());
    assertThat(sql(due)).isEqualTo("SELECT * FROM promotion_reconcile_task "
        + "FORCE INDEX (idx_promotion_reconcile_status_run_position) "
        + "WHERE status IN ('PENDING', 'RETRY') AND next_run_at<=? "
        + "ORDER BY status, next_run_at, position_id LIMIT ? FOR UPDATE SKIP LOCKED");
    assertThat(bindings(due)).containsExactly("now", "limit");
    BoundSql expired = bound(RECONCILE, "selectExpiredLeasesForUpdate", parameters());
    assertThat(sql(expired)).isEqualTo("SELECT * FROM promotion_reconcile_task "
        + "FORCE INDEX (idx_promotion_reconcile_status_lease_position) "
        + "WHERE status='PROCESSING' AND locked_until<=? "
        + "ORDER BY locked_until, position_id LIMIT ? FOR UPDATE SKIP LOCKED");
    assertThat(bindings(expired)).containsExactly("now", "limit");
    assertThat(sql(bound(RECONCILE, "claim", parameters())))
        .contains("WHERE position_id=? AND request_version=?",
            "next_run_at<=?", "locked_until<=?", "locked_by=?", "locked_until=?");
    for (String method : List.of("markProcessed", "markRetry")) {
      assertThat(sql(bound(RECONCILE, method, parameters())))
          .contains("WHERE position_id=? AND status='PROCESSING' AND locked_by=?",
              "processed_version<?", "request_version>=?", "locked_by=NULL", "locked_until=NULL");
    }
    assertThat(sql(bound(RECONCILE, "markProcessed", parameters())))
        .contains("processed_version=?", "CASE WHEN request_version=? THEN 'DONE' ELSE 'PENDING' END",
            "next_run_at=?", "attempt_count=0", "last_error=NULL");
    assertThat(sql(bound(RECONCILE, "markRetry", parameters())))
        .contains("status='RETRY'", "attempt_count=?", "next_run_at=?", "last_error=?")
        .doesNotContain("SET processed_version=");
  }

  @Test
  void reconciliationExecutionAndCompletionFenceExpiredLeaseOwners() {
    BoundSql lock = bound(RECONCILE, "selectByPositionForUpdate", parameters());
    assertThat(sql(lock)).isEqualTo("SELECT * FROM promotion_reconcile_task WHERE position_id=? FOR UPDATE");
    assertThat(bindings(lock)).containsExactly("positionId");
    assertThat(sql(bound(RECONCILE, "markProcessed", parameters()))).contains("locked_until>?");
    assertThat(sql(bound(RECONCILE, "markRetry", parameters()))).contains("locked_until>?");
  }

  @Test
  void reconciliationRequestsIncrementVersionWithoutStealingProcessingLease() {
    assertThat(sql(bound(RECONCILE, "upsertRequest", parameters())))
        .contains("INSERT INTO promotion_reconcile_task", "ON DUPLICATE KEY UPDATE",
            "request_version=request_version+1", "next_run_at=LEAST(next_run_at, VALUES(next_run_at))",
            "status=CASE WHEN status='PROCESSING' THEN 'PROCESSING' ELSE 'PENDING' END")
        .doesNotContain("locked_by=", "locked_until=", "processed_version=");
  }

  @Test
  void claimableFacadesPassOnlyRemainingLimitToLeaseReclamation() throws Exception {
    for (String mapperName : List.of(OUTBOX, RECONCILE)) {
      Class<?> entityType = Class.forName(mapperName.replace("Mapper", ""));
      List<Object> due = List.of(entityType.getConstructor().newInstance(), entityType.getConstructor().newInstance());
      List<Object> expired = List.of(entityType.getConstructor().newInstance(), entityType.getConstructor().newInstance());
      List<String> calls = new ArrayList<>();
      Object mapper = mapperWithCandidates(mapperName, due, expired, calls);
      Object result = mapper.getClass().getMethod("selectClaimableForUpdate", LocalDateTime.class, int.class)
          .invoke(mapper, parameters().get("now"), 3);
      assertThat(result).isEqualTo(List.of(due.get(0), due.get(1), expired.get(0)));
      assertThat(calls).containsExactly("selectDueForUpdate:3", "selectExpiredLeasesForUpdate:1");
    }
  }

  @Test
  void claimableFacadesDoNotReclaimLeasesWhenDueBatchIsFull() throws Exception {
    for (String mapperName : List.of(OUTBOX, RECONCILE)) {
      Class<?> entityType = Class.forName(mapperName.replace("Mapper", ""));
      List<Object> due = List.of(entityType.getConstructor().newInstance(), entityType.getConstructor().newInstance());
      List<String> calls = new ArrayList<>();
      Object mapper = mapperWithCandidates(mapperName, due, due, calls);
      Object result = mapper.getClass().getMethod("selectClaimableForUpdate", LocalDateTime.class, int.class)
          .invoke(mapper, parameters().get("now"), 2);
      assertThat(result).isEqualTo(due);
      assertThat(calls).containsExactly("selectDueForUpdate:2");
    }
  }

  @Test
  void claimableFacadesSkipAllSqlForNonPositiveLimits() throws Exception {
    for (String mapperName : List.of(OUTBOX, RECONCILE)) {
      List<String> calls = new ArrayList<>();
      Object mapper = mapperWithCandidates(mapperName, List.of(), List.of(), calls);
      for (int limit : List.of(0, -1)) {
        assertThat((List<?>) mapper.getClass().getMethod("selectClaimableForUpdate", LocalDateTime.class, int.class)
            .invoke(mapper, parameters().get("now"), limit)).isEmpty();
      }
      assertThat(calls).isEmpty();
    }
  }

  private Object mapperWithCandidates(String mapperName, List<?> due, List<?> expired, List<String> calls)
      throws Exception {
    Class<?> mapperType = Class.forName(mapperName);
    assertThat(mapperType.getMethod("selectClaimableForUpdate", LocalDateTime.class, int.class).isDefault())
        .as("claimable facade delegates to separately indexed query paths").isTrue();
    return Proxy.newProxyInstance(mapperType.getClassLoader(), new Class<?>[] {mapperType},
        (proxy, method, args) -> {
          if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
          }
          if (method.getName().equals("selectDueForUpdate") || method.getName().equals("selectExpiredLeasesForUpdate")) {
            int limit = (int) args[1];
            calls.add(method.getName() + ":" + limit);
            return (method.getName().equals("selectDueForUpdate") ? due : expired).stream().limit(limit).toList();
          }
          throw new AssertionError("Unexpected mapper call: " + method);
        });
  }

  @Test
  void entitiesMapStage5ColumnsUsingExistingIdConventions() throws Exception {
    assertEntity("com.volunteerflow.infrastructure.event.OutboxEvent", "outbox_event", "id", IdType.ASSIGN_ID,
        Map.of("topic", String.class, "messageKey", String.class, "lockedBy", String.class,
            "lockedUntil", LocalDateTime.class, "lastError", String.class,
            "publishedPartition", Integer.class, "publishedOffset", Long.class));
    assertEntity("com.volunteerflow.infrastructure.event.ConsumedEvent", "consumed_event", "id", IdType.ASSIGN_ID,
        Map.of("consumerName", String.class, "eventId", String.class, "processedAt", LocalDateTime.class));
    assertEntity("com.volunteerflow.registration.PromotionReconcileTask", "promotion_reconcile_task", "positionId", IdType.INPUT,
        Map.of("organizationId", Long.class, "status", String.class,
            "requestVersion", Long.class, "processedVersion", Long.class,
            "nextRunAt", LocalDateTime.class, "lockedBy", String.class,
            "lockedUntil", LocalDateTime.class, "attemptCount", Integer.class,
            "createTime", LocalDateTime.class, "updateTime", LocalDateTime.class));
  }

  private void assertEntity(String name, String table, String key, IdType idType, Map<String, Class<?>> fields)
      throws Exception {
    Class<?> type = assertDoesNotThrow(() -> Class.forName(name));
    assertThat(type.getAnnotation(TableName.class).value()).isEqualTo(table);
    assertThat(type.getDeclaredField(key).getAnnotation(TableId.class).type()).isEqualTo(idType);
    for (Map.Entry<String, Class<?>> field : fields.entrySet()) {
      assertThat(type.getDeclaredField(field.getKey()).getType()).isEqualTo(field.getValue());
    }
  }

  private Map<String, Object> parameters() {
    Map<String, Object> result = new HashMap<>();
    for (String name : List.of("now", "lockedUntil", "publishedAt", "nextRetryAt", "nextRunAt", "cutoff", "cursorTime")) {
      result.put(name, LocalDateTime.of(2026, 10, 18, 12, 0));
    }
    result.put("ids", List.of(1L, 2L));
    result.put("cursorId", 10L);
    result.put("limit", 2);
    return result;
  }

  private BoundSql bound(String mapper, String method, Map<String, Object> parameters) {
    Class<?> type = assertDoesNotThrow(() -> Class.forName(mapper));
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.addMapper(type);
    assertThat(configuration.hasStatement(mapper + "." + method)).as("mapped query %s", method).isTrue();
    return configuration.getMappedStatement(mapper + "." + method).getBoundSql(parameters);
  }

  private String sql(BoundSql statement) {
    return statement.getSql().replaceAll("\\s+", " ").trim();
  }

  private List<String> bindings(BoundSql statement) {
    return statement.getParameterMappings().stream().map(mapping -> mapping.getProperty()).toList();
  }
}
