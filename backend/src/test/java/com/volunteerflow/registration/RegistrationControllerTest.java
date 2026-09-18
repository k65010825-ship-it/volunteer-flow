package com.volunteerflow.registration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.infrastructure.web.GlobalExceptionHandler;
import com.volunteerflow.registration.RegistrationSubmissionService.RegistrationResult;
import com.volunteerflow.registration.RegistrationViews.ManagedRegistrationPage;
import com.volunteerflow.registration.RegistrationViews.OwnRegistrationView;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Exercises the HTTP contract while service mocks isolate database transactions. */
class RegistrationControllerTest {
  private RegistrationSubmissionService submissions;
  private RegistrationQueryService queries;
  private RegistrationCancellationService cancellations;
  private RegistrationReviewService reviews;
  private PromotionService promotions;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    submissions = mock(RegistrationSubmissionService.class);
    queries = mock(RegistrationQueryService.class);
    cancellations = mock(RegistrationCancellationService.class);
    reviews = mock(RegistrationReviewService.class);
    promotions = mock(PromotionService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new RegistrationController(
                    submissions, queries, cancellations, reviews, promotions))
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
  void submitDerivesIdentityFromPrincipalAndReturnsCreatedEnvelope() throws Exception {
    when(submissions.submit(eq(21L), eq(10L), any()))
        .thenReturn(new RegistrationResult(30L, 40L, "CONFIRMED", null, null));
    mvc.perform(
            post("/api/v1/activities/10/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"positionId\":20,\"answers\":[],\"userId\":999}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.registrationId").value(30))
        .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    verify(submissions)
        .submit(
            21L, 10L, new RegistrationSubmissionService.SubmitRegistrationRequest(20L, List.of()));
  }

  @Test
  void submissionPreservesScopedAnswersWithoutTrustingQuestionMetadata() throws Exception {
    when(submissions.submit(eq(21L), eq(10L), any()))
        .thenReturn(new RegistrationResult(30L, 40L, "CONFIRMED", null, null));
    mvc.perform(
            post("/api/v1/activities/10/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"positionId":20,"answers":[
                      {"questionScope":"ACTIVITY","questionId":7,"answer":true,"title":"ignored"},
                      {"questionScope":"POSITION","questionId":8,"answer":["A","B"]}
                    ]}
                    """))
        .andExpect(status().isCreated());
    ObjectMapper mapper = new ObjectMapper();
    verify(submissions)
        .submit(
            21L,
            10L,
            new RegistrationSubmissionService.SubmitRegistrationRequest(
                20L,
                List.of(
                    new RegistrationSubmissionService.AnswerInput(
                        "ACTIVITY", 7L, mapper.readTree("true")),
                    new RegistrationSubmissionService.AnswerInput(
                        "POSITION", 8L, mapper.readTree("[\"A\",\"B\"]")))));
  }

  @Test
  void reviewReasonReachesServiceBeforeTrimmedLengthValidation() throws Exception {
    String rawReason = " " + "x".repeat(500) + " ";
    when(reviews.decide(eq(21L), eq(30L), any())).thenReturn(cycle("CONFIRMED"));
    mvc.perform(
            post("/api/v1/registrations/30/review-decisions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"CONFIRM\",\"reason\":\"" + rawReason + "\"}"))
        .andExpect(status().isOk());
    verify(reviews)
        .decide(
            21L, 30L, new RegistrationReviewService.ReviewDecisionRequest("CONFIRM", rawReason));
  }

  @Test
  void missingReviewReasonPreservesSemantic422() throws Exception {
    when(reviews.decide(eq(21L), eq(30L), any()))
        .thenThrow(
            new BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INVALID_REVIEW_REASON",
                "A review reason is required"));
    mvc.perform(
            post("/api/v1/registrations/30/review-decisions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"CONFIRM\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("INVALID_REVIEW_REASON"));
    verify(reviews)
        .decide(21L, 30L, new RegistrationReviewService.ReviewDecisionRequest("CONFIRM", null));
  }

  @Test
  void memberQueriesOnlyUsePrincipalIdentity() throws Exception {
    when(queries.getOwn(21L, 30L)).thenReturn(ownView());
    when(queries.listOwn(21L)).thenReturn(List.of(ownView()));
    mvc.perform(get("/api/v1/registrations/30").param("userId", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.cycleId").value(40));
    mvc.perform(get("/api/v1/users/me/registrations").param("userId", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].registrationId").value(30));
    verify(queries).getOwn(21L, 30L);
    verify(queries).listOwn(21L);
  }

  @Test
  void cancellationReturnsUpdatedStateAndDoesNotExposePersistenceFields() throws Exception {
    RegistrationCycle cycle = cycle("CANCELED");
    when(cancellations.cancel(eq(21L), eq(30L), any())).thenReturn(cycle);
    mvc.perform(
            post("/api/v1/registrations/30/cancellation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"schedule\",\"userId\":999}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CANCELED"))
        .andExpect(jsonPath("$.data.version").doesNotExist());
    verify(cancellations)
        .cancel(21L, 30L, new RegistrationCancellationService.CancellationRequest("schedule"));
  }

  @Test
  void promotionResponseUsesPrincipalAndReturnsSafeOfferView() throws Exception {
    when(promotions.respond(eq(21L), eq(50L), any())).thenReturn(offer("ACCEPTED"));
    mvc.perform(
            post("/api/v1/promotion-offers/50/responses")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\" accept \",\"userId\":999}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.data.createdBy").doesNotExist());
    verify(promotions).respond(21L, 50L, new PromotionService.PromotionResponseRequest(" accept "));
  }

  @Test
  void adminQueriesForwardFilterAndBoundPagination() throws Exception {
    when(queries.listForPosition(21L, 20L, "WAITLISTED", 1, 100))
        .thenReturn(new ManagedRegistrationPage(List.of(), 0, 1, 100));
    mvc.perform(
            get("/api/v1/positions/20/registrations")
                .param("status", "WAITLISTED")
                .param("page", "-2")
                .param("size", "1000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(100));
    verify(queries).listForPosition(21L, 20L, "WAITLISTED", 1, 100);
  }

  @Test
  void adminQueryDefaultsToFirstPageOfTwenty() throws Exception {
    when(queries.listForPosition(21L, 20L, null, 1, 20))
        .thenReturn(new ManagedRegistrationPage(List.of(), 0, 1, 20));
    mvc.perform(get("/api/v1/positions/20/registrations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.size").value(20));
  }

  @Test
  void reviewUsesPrincipalAndAcceptsServiceCompatibleDecision() throws Exception {
    when(reviews.decide(eq(21L), eq(30L), any())).thenReturn(cycle("CONFIRMED"));
    mvc.perform(
            post("/api/v1/registrations/30/review-decisions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\" confirm \",\"reason\":\"qualified\",\"actorId\":999}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    verify(reviews)
        .decide(
            21L,
            30L,
            new RegistrationReviewService.ReviewDecisionRequest(" confirm ", "qualified"));
  }

  @Test
  void manualOfferReturnsCreatedAndUsesPrincipal() throws Exception {
    when(promotions.createManualOffer(eq(21L), eq(30L), any())).thenReturn(offer("PENDING"));
    mvc.perform(
            post("/api/v1/registrations/30/promotion-offers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"selected\",\"actorId\":999}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(50));
    verify(promotions)
        .createManualOffer(21L, 30L, new PromotionService.PromotionRequest("selected"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"positionId\":0,\"answers\":[]}",
        "{\"positionId\":20,\"answers\":[null]}",
        "{\"positionId\":20,\"answers\":[{\"questionScope\":\"USER\",\"questionId\":1,\"answer\":true}]}"
      })
  void malformedSubmissionIsRejectedBeforeService(String body) throws Exception {
    mvc.perform(
            post("/api/v1/activities/10/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    verifyNoInteractions(submissions);
  }

  @Test
  void moreThanTenAnswersIsRejected() throws Exception {
    String answer = "{\"questionScope\":\"ACTIVITY\",\"questionId\":1,\"answer\":true}";
    String body =
        "{\"positionId\":20,\"answers\":["
            + String.join(",", Collections.nCopies(11, answer))
            + "]}";
    mvc.perform(
            post("/api/v1/activities/10/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(submissions);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/v1/registrations/30/review-decisions",
        "/api/v1/promotion-offers/50/responses"
      })
  void unknownDecisionIsRejectedBeforeService(String path) throws Exception {
    mvc.perform(
            post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"DELETE\",\"reason\":\"reason\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(reviews, promotions);
  }

  @ParameterizedTest
  @ValueSource(ints = {403, 404, 409, 422})
  void serviceErrorsKeepTheirStatusAndStableCode(int status) throws Exception {
    when(queries.getOwn(21L, 30L))
        .thenThrow(
            new BusinessException(
                HttpStatus.valueOf(status), "REGISTRATION_DENIED", "Request denied"));
    mvc.perform(get("/api/v1/registrations/30").requestAttr("requestId", "registration-request"))
        .andExpect(status().is(status))
        .andExpect(jsonPath("$.code").value("REGISTRATION_DENIED"))
        .andExpect(jsonPath("$.requestId").value("registration-request"));
  }

  private OwnRegistrationView ownView() {
    return new OwnRegistrationView(
        30L,
        1L,
        10L,
        40L,
        20L,
        1,
        "CONFIRMED",
        LocalDateTime.of(2026, 9, 17, 10, 0),
        null,
        null,
        0L,
        List.of(),
        null);
  }

  private RegistrationCycle cycle(String status) {
    RegistrationCycle cycle = new RegistrationCycle();
    cycle.setId(40L);
    cycle.setRegistrationId(30L);
    cycle.setStatus(status);
    cycle.setVersion(1);
    return cycle;
  }

  private PromotionOffer offer(String status) {
    PromotionOffer offer = new PromotionOffer();
    offer.setId(50L);
    offer.setStatus(status);
    offer.setCreatedBy(99L);
    offer.setExpiresAt(LocalDateTime.of(2026, 9, 17, 11, 0));
    return offer;
  }
}
