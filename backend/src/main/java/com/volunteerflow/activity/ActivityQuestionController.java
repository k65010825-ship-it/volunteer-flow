package com.volunteerflow.activity;

import com.volunteerflow.auth.CurrentUser;
import com.volunteerflow.infrastructure.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter for registration-question management and public form reads. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
public class ActivityQuestionController {
  private final ActivityQuestionService questions;
  private final ActivityRegistrationPolicyService policies;

  public ActivityQuestionController(
      ActivityQuestionService questions, ActivityRegistrationPolicyService policies) {
    this.questions = questions;
    this.policies = policies;
  }

  @PostMapping("/activities/{activityId}/questions")
  public ResponseEntity<ApiResponse<ActivityQuestion>> createActivityQuestion(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long activityId,
      @Valid @RequestBody ActivityQuestionService.QuestionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(questions.createActivityQuestion(currentUser.id(), activityId, request)));
  }

  @PutMapping("/activities/{activityId}/questions/{questionId}")
  public ApiResponse<ActivityQuestion> updateActivityQuestion(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long activityId,
      @PathVariable Long questionId,
      @Valid @RequestBody ActivityQuestionService.QuestionRequest request) {
    return ApiResponse.of(
        questions.updateActivityQuestion(currentUser.id(), activityId, questionId, request));
  }

  @DeleteMapping("/activities/{activityId}/questions/{questionId}")
  public ResponseEntity<Void> deleteActivityQuestion(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long activityId,
      @PathVariable Long questionId) {
    questions.deleteActivityQuestion(currentUser.id(), activityId, questionId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/positions/{positionId}/questions")
  public ResponseEntity<ApiResponse<ActivityPositionQuestion>> createPositionQuestion(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long positionId,
      @Valid @RequestBody ActivityQuestionService.QuestionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.of(questions.createPositionQuestion(currentUser.id(), positionId, request)));
  }

  @PutMapping("/positions/{positionId}/questions/{questionId}")
  public ApiResponse<ActivityPositionQuestion> updatePositionQuestion(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long positionId,
      @PathVariable Long questionId,
      @Valid @RequestBody ActivityQuestionService.QuestionRequest request) {
    return ApiResponse.of(
        questions.updatePositionQuestion(currentUser.id(), positionId, questionId, request));
  }

  @DeleteMapping("/positions/{positionId}/questions/{questionId}")
  public ResponseEntity<Void> deletePositionQuestion(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long positionId,
      @PathVariable Long questionId) {
    questions.deletePositionQuestion(currentUser.id(), positionId, questionId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/activities/{activityId}/registration-form")
  public ApiResponse<ActivityRegistrationPolicyService.RegistrationForm> registrationForm(
      @AuthenticationPrincipal CurrentUser currentUser,
      @PathVariable Long activityId,
      @RequestParam Long positionId) {
    return ApiResponse.of(policies.loadForm(currentUser.id(), activityId, positionId));
  }
}
