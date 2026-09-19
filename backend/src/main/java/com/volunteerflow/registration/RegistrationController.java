package com.volunteerflow.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.ApiResponse;
import com.volunteerflow.registration.RegistrationSubmissionService.AnswerInput;
import com.volunteerflow.registration.RegistrationSubmissionService.RegistrationResult;
import com.volunteerflow.registration.RegistrationSubmissionService.SubmitRegistrationRequest;
import com.volunteerflow.registration.RegistrationViews.ManagedRegistrationPage;
import com.volunteerflow.registration.RegistrationViews.OwnOfferView;
import com.volunteerflow.registration.RegistrationViews.OwnRegistrationView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP boundary only: identity comes from JWT; ownership and RBAC stay in the services. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
public class RegistrationController {
  private final RegistrationSubmissionService submissions;
  private final RegistrationQueryService queries;
  private final RegistrationCancellationService cancellations;
  private final RegistrationReviewService reviews;
  private final PromotionService promotions;

  public RegistrationController(
      RegistrationSubmissionService submissions,
      RegistrationQueryService queries,
      RegistrationCancellationService cancellations,
      RegistrationReviewService reviews,
      PromotionService promotions) {
    this.submissions = submissions;
    this.queries = queries;
    this.cancellations = cancellations;
    this.reviews = reviews;
    this.promotions = promotions;
  }

  @PostMapping("/activities/{activityId}/registrations")
  public ResponseEntity<ApiResponse<RegistrationResult>> submit(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable("activityId") Long activityId,
      @Valid @RequestBody SubmissionBody body) {
    List<AnswerInput> answers =
        body.answers().stream()
            .map(
                answer ->
                    new AnswerInput(answer.questionScope(), answer.questionId(), answer.answer()))
            .toList();
    RegistrationResult result =
        submissions.submit(
            currentUser.id(),
            activityId,
            new SubmitRegistrationRequest(body.positionId(), answers));
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
  }

  @GetMapping("/registrations/{registrationId}")
  public ApiResponse<OwnRegistrationView> getOwn(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable("registrationId") Long registrationId) {
    return ApiResponse.of(queries.getOwn(currentUser.id(), registrationId));
  }

  @GetMapping("/users/me/registrations")
  public ApiResponse<List<OwnRegistrationView>> listOwn(
      @AuthenticationPrincipal CurrentUser currentUser) {
    return ApiResponse.of(queries.listOwn(currentUser.id()));
  }

  @PostMapping("/registrations/{registrationId}/cancellation")
  public ApiResponse<RegistrationState> cancel(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable("registrationId") Long registrationId,
      @Valid @RequestBody RegistrationCancellationService.CancellationRequest body) {
    return ApiResponse.of(
        RegistrationState.from(cancellations.cancel(currentUser.id(), registrationId, body)));
  }

  @PostMapping("/promotion-offers/{offerId}/responses")
  public ApiResponse<OwnOfferView> respond(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable("offerId") Long offerId,
      @Valid @RequestBody PromotionResponseBody body) {
    PromotionOffer offer =
        promotions.respond(
            currentUser.id(),
            offerId,
            new PromotionService.PromotionResponseRequest(body.decision()));
    return ApiResponse.of(offerView(offer));
  }

  @GetMapping("/positions/{positionId}/registrations")
  public ApiResponse<ManagedRegistrationPage> listForPosition(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable("positionId") Long positionId,
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "page", defaultValue = "1") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {
    // Match the query service's bounds even if a caller supplies an excessive page size.
    return ApiResponse.of(
        queries.listForPosition(
            currentUser.id(),
            positionId,
            status,
            Math.max(1, page),
            Math.min(100, Math.max(1, size))));
  }

  @PostMapping("/registrations/{registrationId}/review-decisions")
  public ApiResponse<RegistrationState> review(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable("registrationId") Long registrationId,
      @Valid @RequestBody ReviewBody body) {
    return ApiResponse.of(
        RegistrationState.from(
            reviews.decide(
                currentUser.id(),
                registrationId,
                new RegistrationReviewService.ReviewDecisionRequest(
                    body.decision(), body.reason()))));
  }

  @PostMapping("/registrations/{registrationId}/promotion-offers")
  public ResponseEntity<ApiResponse<OwnOfferView>> createManualOffer(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable("registrationId") Long registrationId,
      @Valid @RequestBody PromotionService.PromotionRequest body) {
    PromotionOffer offer = promotions.createManualOffer(currentUser.id(), registrationId, body);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(offerView(offer)));
  }

  private OwnOfferView offerView(PromotionOffer offer) {
    return new OwnOfferView(
        offer.getId(), offer.getStatus(), offer.getExpiresAt(), offer.getReason());
  }

  public record SubmissionBody(
      @NotNull @Positive Long positionId,
      @NotNull @Size(max = 10) List<@NotNull @Valid AnswerBody> answers) {}

  public record AnswerBody(
      @NotBlank @Pattern(regexp = "ACTIVITY|POSITION") String questionScope,
      @NotNull @Positive Long questionId,
      JsonNode answer) {}

  public record PromotionResponseBody(
      @NotBlank @Pattern(regexp = "(?i)[\\x00-\\x20]*(ACCEPT|DECLINE)[\\x00-\\x20]*")
          String decision) {}

  // Reason validation belongs to the service: trim first, then require 1-500 characters (422).
  public record ReviewBody(
      @NotBlank @Pattern(regexp = "(?i)[\\x00-\\x20]*(CONFIRM|WAITLIST|REJECT)[\\x00-\\x20]*")
          String decision,
      String reason) {}

  /** Commands expose their result, not persistence bookkeeping or reviewer identities. */
  public record RegistrationState(Long registrationId, Long cycleId, String status) {
    static RegistrationState from(RegistrationCycle cycle) {
      return new RegistrationState(cycle.getRegistrationId(), cycle.getId(), cycle.getStatus());
    }
  }
}
