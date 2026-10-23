package com.volunteerflow.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Deliberately separate API projections from the append-only persistence entity. */
public final class AuditLogViews {
  private AuditLogViews() {}

  public record Filter(Long actorUserId, String action, String resourceType, Long resourceId,
                       LocalDateTime from, LocalDateTime to) {}

  public record Item(Long id, Long actorUserId, String action, String resourceType,
                     Long resourceId, LocalDateTime createTime, List<String> changedFields,
                     Map<String, JsonNode> before, Map<String, JsonNode> after) {}

  public record Meta(long total, long page, long size) {}

  public record AuditPage(List<Item> data, Meta meta) {}
}
