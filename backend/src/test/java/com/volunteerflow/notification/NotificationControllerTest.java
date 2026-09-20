package com.volunteerflow.notification;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.infrastructure.web.GlobalExceptionHandler;
import com.volunteerflow.notification.NotificationViews.NotificationItem;
import com.volunteerflow.notification.NotificationViews.NotificationPage;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class NotificationControllerTest {
  private NotificationService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(NotificationService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new NotificationController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();
    CurrentUser user = new CurrentUser(21L, "member", "Member", "20260021", "contact", "USER");
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void listUsesPrincipalIdentityAndClampsPageSizeToFifty() throws Exception {
    LocalDateTime createdAt = LocalDateTime.of(2026, 9, 20, 12, 30);
    NotificationPage page =
        new NotificationPage(
            List.of(
                new NotificationItem(
                    900L,
                    "PROMOTION_OFFER",
                    "递补邀请",
                    "请及时确认",
                    "PROMOTION_OFFER",
                    99L,
                    null,
                    createdAt)),
            "next-cursor",
            true);
    when(service.page(21L, "opaque-cursor", 50)).thenReturn(page);

    mvc.perform(
            get("/api/v1/users/me/notifications")
                .param("cursor", "opaque-cursor")
                .param("size", "1000")
                .param("userId", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].id").value(900))
        .andExpect(jsonPath("$.data.items[0].deduplicationKey").doesNotExist())
        .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"))
        .andExpect(jsonPath("$.data.hasMore").value(true));
    verify(service).page(21L, "opaque-cursor", 50);
  }

  @Test
  void listClampsPageSizeToOne() throws Exception {
    when(service.page(21L, null, 1))
        .thenReturn(new NotificationPage(List.of(), null, false));

    mvc.perform(get("/api/v1/users/me/notifications").param("size", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items").isEmpty());

    verify(service).page(21L, null, 1);
  }

  @Test
  void unreadCountUsesPrincipalIdentity() throws Exception {
    when(service.unreadCount(21L)).thenReturn(3L);

    mvc.perform(get("/api/v1/users/me/notifications/unread-count").param("userId", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.unreadCount").value(3));

    verify(service).unreadCount(21L);
  }

  @Test
  void markReadUsesPrincipalAndNotificationId() throws Exception {
    mvc.perform(post("/api/v1/notifications/900/read").param("userId", "999"))
        .andExpect(status().isNoContent());

    verify(service).markRead(21L, 900L);
  }

  @Test
  void markAllReadUsesPrincipalIdentity() throws Exception {
    mvc.perform(post("/api/v1/users/me/notifications/read-all").param("userId", "999"))
        .andExpect(status().isNoContent());

    verify(service).markAllRead(21L);
  }

  @Test
  void malformedCursorKeepsTheStableBusinessErrorCode() throws Exception {
    when(service.page(21L, "broken", 20))
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_NOTIFICATION_CURSOR",
                "Notification cursor is invalid"));

    mvc.perform(
            get("/api/v1/users/me/notifications")
                .param("cursor", "broken")
                .requestAttr("requestId", "notification-request"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_NOTIFICATION_CURSOR"))
        .andExpect(jsonPath("$.requestId").value("notification-request"));
  }
}
