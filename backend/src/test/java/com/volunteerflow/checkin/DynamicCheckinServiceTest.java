package com.volunteerflow.checkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.volunteerflow.checkin.AttendanceQueryService.DynamicCheckinTarget;
import com.volunteerflow.checkin.DynamicCheckinService.DynamicCheckinRequest;
import com.volunteerflow.checkin.DynamicCheckinTokenStore.InvalidCredentialException;
import com.volunteerflow.checkin.DynamicCheckinTokenStore.StoreUnavailableException;
import com.volunteerflow.checkin.DynamicCheckinTokenStore.VerifiedCredential;
import com.volunteerflow.infrastructure.web.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DynamicCheckinServiceTest {
  private final AttendanceQueryService queries = mock(AttendanceQueryService.class);
  private final DynamicCheckinTokenStore tokens = mock(DynamicCheckinTokenStore.class);
  private final CheckinService checkins = mock(CheckinService.class);
  private final Instant now = Instant.parse("2026-10-16T04:00:10Z");
  private final DynamicCheckinService service = new DynamicCheckinService(
      queries, tokens, checkins, Clock.fixed(now, ZoneOffset.UTC));
  private final DynamicCheckinRequest request =
      new DynamicCheckinRequest(1_792_137_600L, "secret-token");

  @BeforeEach
  void arrangeMembershipRouting() {
    when(queries.dynamicSelfRequest(21L, 101L)).thenReturn(new DynamicCheckinTarget(11L));
  }

  @Test
  void redisFailureDoesNotCallTransactionalService() {
    when(tokens.verify(101L, request.windowEpochSecond(), request.token(), now))
        .thenThrow(new StoreUnavailableException("down"));

    rejects(() -> service.selfCheckin(21L, 101L, request), 503,
        "DYNAMIC_CHECKIN_UNAVAILABLE");
    verifyNoInteractions(checkins);
  }

  @Test
  void invalidCredentialDoesNotCallTransactionalService() {
    when(tokens.verify(101L, request.windowEpochSecond(), request.token(), now))
        .thenThrow(new InvalidCredentialException());

    rejects(() -> service.selfCheckin(21L, 101L, request), 422,
        "INVALID_DYNAMIC_CHECKIN_CODE");
    verifyNoInteractions(checkins);
  }

  @Test
  void validCredentialCallsTransactionalServiceWithProof() {
    var proof = new VerifiedCredential(101L, request.windowEpochSecond(), now.plusSeconds(35));
    var expected = new CheckinService.CheckinResult(401L, 11L, 21L, "SELF_SERVICE",
        java.time.LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    when(tokens.verify(101L, request.windowEpochSecond(), request.token(), now)).thenReturn(proof);
    when(checkins.dynamicSelfCheckin(21L, 101L, 11L, proof)).thenReturn(expected);

    assertThat(service.selfCheckin(21L, 101L, request)).isSameAs(expected);

    var order = inOrder(queries, tokens, checkins);
    order.verify(queries).dynamicSelfRequest(21L, 101L);
    order.verify(tokens).verify(101L, request.windowEpochSecond(), request.token(), now);
    order.verify(checkins).dynamicSelfCheckin(21L, 101L, 11L, proof);
  }

  @Test
  void proofForAnotherSessionIsRejectedBeforeTransaction() {
    when(queries.dynamicSelfRequest(21L, 102L)).thenReturn(new DynamicCheckinTarget(11L));
    when(tokens.verify(102L, request.windowEpochSecond(), request.token(), now))
        .thenThrow(new InvalidCredentialException());

    rejects(() -> service.selfCheckin(21L, 102L, request), 422,
        "INVALID_DYNAMIC_CHECKIN_CODE");
    verifyNoInteractions(checkins);
  }

  @Test
  void requestAndMappedExceptionStringsNeverExposeToken() {
    when(tokens.verify(101L, request.windowEpochSecond(), request.token(), now))
        .thenThrow(new InvalidCredentialException());

    assertThat(request.toString()).doesNotContain("secret-token");
    assertThatThrownBy(() -> service.selfCheckin(21L, 101L, request))
        .isInstanceOfSatisfying(BusinessException.class,
            error -> assertThat(error.toString()).doesNotContain("secret-token"));
  }

  private void rejects(Runnable action, int status, String code) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, error -> {
      assertThat(error.status().value()).isEqualTo(status);
      assertThat(error.code()).isEqualTo(code);
    });
  }
}
