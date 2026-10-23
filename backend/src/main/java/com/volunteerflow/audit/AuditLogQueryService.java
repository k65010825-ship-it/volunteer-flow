package com.volunteerflow.audit;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.audit.AuditLogViews.*;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.rbac.OrganizationAuthorizationService;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Organization-scoped audit reads never mutate the audit trail. */
@Service
@Profile("!test")
public class AuditLogQueryService {
  private static final Set<String> NUMBER_FIELDS = Set.of(
      "capacity", "promotionTimeoutMinutes", "confirmedCount", "activityId", "registrationCycleId");
  private static final Set<String> TIME_FIELDS = Set.of(
      "registrationEndAt", "freeCancelDeadlineAt", "activityStartAt", "activityEndAt",
      "serviceStartAt", "serviceEndAt");
  private static final Set<String> TEXT_FIELDS = Set.of(
      "title", "name", "description", "location", "meetingLocation");
  private static final Set<String> STATUSES = Set.of(
      "DRAFT", "PUBLISHED", "ACTIVE", "FINISHED", "CANCELLED", "DISABLED");
  private final AuditLogMapper logs;
  private final OrganizationAuthorizationService authorization;
  private final ObjectMapper json;

  public AuditLogQueryService(AuditLogMapper logs,
      OrganizationAuthorizationService authorization, ObjectMapper json) {
    this.logs = logs;
    this.authorization = authorization;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public AuditPage page(Long actorId, Long organizationId, int page, int size, Filter filter) {
    authorization.requirePermission(actorId, organizationId, "audit:read");
    Filter normalized = normalize(filter);
    long safePage = Math.max(1, page);
    long safeSize = Math.min(100, Math.max(1, size));
    var result = logs.selectForOrganization(new Page<>(safePage, safeSize), organizationId, normalized);
    for (AuditLog log : result.getRecords()) {
      if (!organizationId.equals(log.getOrganizationId())) {
        throw new IllegalStateException("Audit query returned out-of-scope data");
      }
    }
    return new AuditPage(result.getRecords().stream().map(this::item).toList(),
        new Meta(result.getTotal(), safePage, safeSize));
  }

  private Filter normalize(Filter filter) {
    if ((filter.actorUserId() != null && filter.actorUserId() <= 0)
        || (filter.resourceId() != null && filter.resourceId() <= 0)
        || (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to()))) {
      throw invalidFilter();
    }
    return new Filter(filter.actorUserId(), text(filter.action(), 96),
        text(filter.resourceType(), 64), filter.resourceId(), filter.from(), filter.to());
  }

  private String text(String value, int maxLength) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.length() > maxLength) {
      throw invalidFilter();
    }
    return trimmed;
  }

  private BusinessException invalidFilter() {
    return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AUDIT_FILTER", "Audit filter is invalid");
  }

  private Item item(AuditLog log) {
    JsonNode before = snapshot(log.getBeforeJson());
    JsonNode after = snapshot(log.getAfterJson());
    Set<String> changed = new TreeSet<>();
    before.fieldNames().forEachRemaining(changed::add);
    after.fieldNames().forEachRemaining(changed::add);
    changed.removeIf(key -> !known(key));
    return new Item(log.getId(), log.getActorUserId(), log.getAction(), log.getResourceType(),
        log.getResourceId(), log.getCreateTime(), List.copyOf(changed), safe(before), safe(after));
  }

  private JsonNode snapshot(String value) {
    if (value != null) {
      try {
        JsonNode parsed = json.readTree(value);
        if (parsed != null && parsed.isObject()) {
          return parsed;
        }
      } catch (JsonProcessingException ignored) {
        // Historical malformed snapshots must not expose raw data or break the whole page.
      }
    }
    return json.createObjectNode();
  }

  private boolean known(String key) {
    return NUMBER_FIELDS.contains(key) || TIME_FIELDS.contains(key) || TEXT_FIELDS.contains(key)
        || "status".equals(key) || "method".equals(key);
  }

  private Map<String, JsonNode> safe(JsonNode snapshot) {
    Map<String, JsonNode> safe = new TreeMap<>();
    snapshot.fields().forEachRemaining(entry -> {
      if (safeValue(entry.getKey(), entry.getValue())) {
        safe.put(entry.getKey(), entry.getValue());
      }
    });
    return Collections.unmodifiableMap(safe);
  }

  private boolean safeValue(String key, JsonNode value) {
    if (TEXT_FIELDS.contains(key) || !known(key)) {
      return false;
    }
    if (value.isNull()) {
      return true;
    }
    if (NUMBER_FIELDS.contains(key)) {
      return value.isIntegralNumber() && value.canConvertToLong();
    }
    if (TIME_FIELDS.contains(key) && value.isTextual()) {
      try {
        LocalDateTime.parse(value.textValue());
        return true;
      } catch (DateTimeParseException ignored) {
        return false;
      }
    }
    return value.isTextual() && (("status".equals(key) && STATUSES.contains(value.textValue()))
        || ("method".equals(key) && Set.of("MANUAL", "SELF_SERVICE").contains(value.textValue())));
  }
}
