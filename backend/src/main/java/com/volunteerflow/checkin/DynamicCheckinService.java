package com.volunteerflow.checkin;

import com.volunteerflow.checkin.AttendanceQueryService.DynamicCheckinTarget;
import com.volunteerflow.checkin.CheckinService.CheckinResult;
import com.volunteerflow.checkin.DynamicCheckinTokenStore.InvalidCredentialException;
import com.volunteerflow.checkin.DynamicCheckinTokenStore.StoreUnavailableException;
import com.volunteerflow.checkin.DynamicCheckinTokenStore.VerifiedCredential;
import com.volunteerflow.infrastructure.web.BusinessException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Performs Redis admission before entering the MySQL check-in transaction.
 * The submitted token never crosses into the transactional service.
 */
@Service
@Profile("!test")
public class DynamicCheckinService {
  private final AttendanceQueryService queries;
  private final DynamicCheckinTokenStore tokens;
  private final CheckinService checkins;
  private final Clock clock;

  public DynamicCheckinService(AttendanceQueryService queries, DynamicCheckinTokenStore tokens,
      CheckinService checkins, Clock clock) {
    this.queries = queries;
    this.tokens = tokens;
    this.checkins = checkins;
    this.clock = clock;
  }

  public CheckinResult selfCheckin(Long userId, Long sessionId, DynamicCheckinRequest request) {
    DynamicCheckinTarget target = queries.dynamicSelfRequest(userId, sessionId);
    Instant now = clock.instant();
    try {
      VerifiedCredential proof = tokens.verify(sessionId, request.windowEpochSecond(),
          request.token(), now);
      return checkins.dynamicSelfCheckin(userId, sessionId, target.activityId(), proof);
    } catch (InvalidCredentialException exception) {
      throw invalidCode();
    } catch (StoreUnavailableException exception) {
      throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
          "DYNAMIC_CHECKIN_UNAVAILABLE", "Dynamic check-in is temporarily unavailable");
    }
  }

  private BusinessException invalidCode() {
    return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
        "INVALID_DYNAMIC_CHECKIN_CODE", "Dynamic check-in code is invalid");
  }

  public record DynamicCheckinRequest(
      long windowEpochSecond,
      String token) {
    @Override
    public String toString() {
      return "DynamicCheckinRequest[windowEpochSecond=" + windowEpochSecond
          + ", token=[REDACTED]]";
    }
  }
}
