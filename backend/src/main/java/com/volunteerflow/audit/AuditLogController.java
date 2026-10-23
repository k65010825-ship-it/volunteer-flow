package com.volunteerflow.audit;

import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.audit.AuditLogViews.AuditPage;
import com.volunteerflow.audit.AuditLogViews.Filter;
import java.time.LocalDateTime;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Read-only organization boundary; no client-supplied identity is trusted. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/organizations/{orgId}/audit-logs")
public class AuditLogController {
  private final AuditLogQueryService queries;

  public AuditLogController(AuditLogQueryService queries) {
    this.queries = queries;
  }

  @GetMapping
  public AuditPage page(
      @AuthenticationPrincipal CurrentUser user,
      @PathVariable("orgId") Long orgId,
      @RequestParam(name = "page", defaultValue = "1") int page,
      @RequestParam(name = "size", defaultValue = "20") int size,
      @RequestParam(name = "actorUserId", required = false) Long actorUserId,
      @RequestParam(name = "action", required = false) String action,
      @RequestParam(name = "resourceType", required = false) String resourceType,
      @RequestParam(name = "resourceId", required = false) Long resourceId,
      @RequestParam(name = "from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
      @RequestParam(name = "to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
    return queries.page(user.id(), orgId, page, size,
        new Filter(actorUserId, action, resourceType, resourceId, from, to));
  }
}
