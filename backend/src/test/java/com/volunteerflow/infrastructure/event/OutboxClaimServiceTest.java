package com.volunteerflow.infrastructure.event;

import static org.assertj.core.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;

class OutboxClaimServiceTest {
  @Test
  void claimsOnlyDueRowsInABoundedBatch() {
    RelayFixture f = new RelayFixture();
    f.properties.setBatchSize(2);
    f.row(1, "PENDING"); f.row(2, "RETRY");
    f.row(3, "PENDING").setNextRetryAt(f.now().plusSeconds(1));
    f.row(4, "PUBLISHED"); f.row(5, "PENDING");
    assertThat(f.claims().claimBatch("worker-a")).extracting(OutboxEvent::getId).containsExactly(1L, 2L);
    assertThat(f.rows.get(1L).getLockedUntil()).isEqualTo(f.now().plusSeconds(60));
    assertThat(f.rows.get(5L).getStatus()).isEqualTo("PENDING");
    assertThat(f.transactions.commits).isEqualTo(1);
  }

  @Test
  void reclaimsExpiredLease() {
    RelayFixture f = new RelayFixture();
    OutboxEvent expired = f.row(1, "PROCESSING");
    expired.setLockedBy("old"); expired.setLockedUntil(f.now().minusSeconds(1));
    OutboxEvent active = f.row(2, "PROCESSING");
    active.setLockedBy("active"); active.setLockedUntil(f.now().plusSeconds(1));
    assertThat(f.claims().claimBatch("new")).extracting(OutboxEvent::getId).containsExactly(1L);
    assertThat(expired.getLockedBy()).isEqualTo("new");
    assertThat(active.getLockedBy()).isEqualTo("active");
  }

  @Test
  void staleWorkerCannotFinishReclaimedRow() {
    RelayFixture f = new RelayFixture();
    OutboxEvent row = f.row(1, "PROCESSING"); row.setLockedBy("new-owner");
    OutboxStateService states = new OutboxStateService(f.mapper, f.clock, f.properties);
    assertThat(states.markPublished(row, "old-owner", new KafkaPublishReceipt(2, 41))).isFalse();
    assertThat(states.markRetry(row, "old-owner")).isFalse();
    assertThat(states.markDead(row, "old-owner")).isFalse();
    assertThat(row.getStatus()).isEqualTo("PROCESSING");
    assertThat(states.markPublished(row, "new-owner", new KafkaPublishReceipt(2, 41))).isTrue();
    assertThat(row.getStatus()).isEqualTo("PUBLISHED");
  }

  @Test
  void retentionDeletesOnlyAnIdBoundedPublishedBatch() {
    RelayFixture f = new RelayFixture(); f.properties.setRetentionBatchSize(2);
    for (long id = 1; id <= 4; id++) f.row(id, "PUBLISHED").setPublishedAt(f.now().minusDays(8));
    f.row(5, "PUBLISHED").setPublishedAt(f.now().minusDays(1)); f.row(6, "DEAD");
    OutboxRetentionJob retention = new OutboxRetentionJob(f.mapper, f.properties, f.clock);
    assertThat(retention.purgeBatch()).isEqualTo(2);
    assertThat(f.rows.keySet()).containsExactly(3L, 4L, 5L, 6L);
    assertThat(retention.purgeBatch()).isEqualTo(2);
    assertThat(f.rows.keySet()).containsExactly(5L, 6L);
  }
}

/** In-memory external DB boundary. Real claim/state/relay services and Spring transaction advice run. */
class RelayFixture {
  final Clock clock = Clock.fixed(Instant.parse("2026-10-19T10:00:00Z"), ZoneOffset.UTC);
  final KafkaEventProperties properties = new KafkaEventProperties();
  final Map<Long, OutboxEvent> rows = new LinkedHashMap<>();
  final TestTransactions transactions = new TestTransactions();
  boolean failPublished;
  final OutboxEventMapper mapper = (OutboxEventMapper) Proxy.newProxyInstance(
      OutboxEventMapper.class.getClassLoader(), new Class<?>[]{OutboxEventMapper.class}, (proxy, method, a) -> {
        if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, a);
        String name = method.getName();
        if (name.equals("selectDueForUpdate") || name.equals("selectExpiredLeasesForUpdate")) {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          LocalDateTime now = (LocalDateTime) a[0];
          return rows.values().stream().filter(row -> name.equals("selectDueForUpdate")
              ? Set.of("PENDING", "RETRY").contains(row.getStatus()) && (row.getNextRetryAt() == null || !row.getNextRetryAt().isAfter(now))
              : row.getStatus().equals("PROCESSING") && row.getLockedUntil() != null && !row.getLockedUntil().isAfter(now))
              .limit((int) a[1]).toList();
        }
        if (name.equals("claim")) {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
          for (Object id : (List<?>) a[0]) {
            OutboxEvent row = rows.get(id); row.setStatus("PROCESSING");
            row.setLockedBy((String) a[1]); row.setLockedUntil((LocalDateTime) a[2]);
          }
          return ((List<?>) a[0]).size();
        }
        if (name.startsWith("mark")) {
          OutboxEvent row = rows.get(a[0]);
          if (!row.getStatus().equals("PROCESSING") || !Objects.equals(row.getLockedBy(), a[1])) return 0;
          if (name.equals("markPublished")) {
            if (failPublished) throw new IllegalStateException("DB unavailable");
            row.setPublishedPartition((int) a[2]); row.setPublishedOffset((long) a[3]); row.setPublishedAt((LocalDateTime) a[4]);
            row.setStatus("PUBLISHED");
          } else if (name.equals("markRetry")) {
            row.setStatus("RETRY"); row.setRetryCount((int) a[2]); row.setNextRetryAt((LocalDateTime) a[3]); row.setLastError((String) a[4]);
          } else { row.setStatus("DEAD"); row.setLastError((String) a[2]); }
          row.setLockedBy(null); row.setLockedUntil(null); return 1;
        }
        if (name.equals("selectPublishedIdsBefore")) {
          return rows.values().stream().filter(row -> row.getStatus().equals("PUBLISHED") && row.getPublishedAt().isBefore((LocalDateTime) a[0]))
              .sorted(Comparator.comparing(OutboxEvent::getPublishedAt).thenComparing(OutboxEvent::getId))
              .limit((int) a[3]).map(OutboxEvent::getId).toList();
        }
        if (name.equals("deletePublishedByIds")) {
          int count = 0;
          for (Object id : (List<?>) a[0]) if (rows.get(id).getStatus().equals("PUBLISHED")) { rows.remove(id); count++; }
          return count;
        }
        throw new AssertionError("Unexpected DB operation " + name);
      });

  LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
  OutboxClaimService claims() { return advised(new OutboxClaimService(mapper, properties, clock)); }
  <T> T advised(T target) {
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    @SuppressWarnings("unchecked") T result = (T) proxy.getProxy(); return result;
  }
  OutboxEvent row(long id, String status) {
    OutboxEvent row = new OutboxEvent(); row.setId(id); row.setStatus(status); row.setRetryCount(0);
    row.setEventId("b1445338-156d-41e3-a131-8f349282125d"); row.setOrganizationId(1001L);
    row.setAggregateType("REGISTRATION"); row.setAggregateId("2001"); row.setEventType("registration.submitted.v1");
    row.setTopic("volunteerflow.registration-events.v1"); row.setMessageKey("1001:3001");
    row.setPayloadJson("""
        {"eventId":"b1445338-156d-41e3-a131-8f349282125d","eventType":"registration.submitted.v1",
        "occurredAt":"2026-10-18T14:30:00Z","organizationId":1001,"aggregateType":"REGISTRATION",
        "aggregateId":"2001","activityId":3001,"requestId":"request-1","payload":{"userId":9,
        "notificationType":"REGISTRATION_SUBMITTED","title":"Result","content":"Confirmed",
        "businessType":"REGISTRATION","businessId":2001}}
        """);
    rows.put(id, row); return row;
  }
  static class TestTransactions extends AbstractPlatformTransactionManager {
    int commits;
    protected Object doGetTransaction() { return new Object(); }
    protected void doBegin(Object t, TransactionDefinition d) {}
    protected void doCommit(DefaultTransactionStatus s) { commits++; }
    protected void doRollback(DefaultTransactionStatus s) {}
  }
}
