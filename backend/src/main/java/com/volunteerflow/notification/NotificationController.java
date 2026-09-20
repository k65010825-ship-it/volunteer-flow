package com.volunteerflow.notification;

import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.ApiResponse;
import com.volunteerflow.notification.NotificationViews.NotificationPage;
import com.volunteerflow.notification.NotificationViews.UnreadCount;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated notification boundary; every operation derives ownership from the JWT principal. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
public class NotificationController {
  private final NotificationService notifications;

  public NotificationController(NotificationService notifications) {
    this.notifications = notifications;
  }

  @GetMapping("/users/me/notifications")
  public ApiResponse<NotificationPage> page(
      @AuthenticationPrincipal CurrentUser currentUser,
      @RequestParam(name = "cursor", required = false) String cursor,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    int boundedSize = Math.min(50, Math.max(1, size));
    return ApiResponse.of(notifications.page(currentUser.id(), cursor, boundedSize));
  }

  @GetMapping("/users/me/notifications/unread-count")
  public ApiResponse<UnreadCount> unreadCount(
      @AuthenticationPrincipal CurrentUser currentUser) {
    return ApiResponse.of(new UnreadCount(notifications.unreadCount(currentUser.id())));
  }

  @PostMapping("/notifications/{id}/read")
  public ResponseEntity<Void> markRead(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable("id") Long notificationId) {
    notifications.markRead(currentUser.id(), notificationId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/users/me/notifications/read-all")
  public ResponseEntity<Void> markAllRead(
      @AuthenticationPrincipal CurrentUser currentUser) {
    notifications.markAllRead(currentUser.id());
    return ResponseEntity.noContent().build();
  }
}
