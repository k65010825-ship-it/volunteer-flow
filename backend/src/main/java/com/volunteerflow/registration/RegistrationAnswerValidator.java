package com.volunteerflow.registration;

import com.fasterxml.jackson.databind.JsonNode;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.QuestionDefinition;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.registration.RegistrationSubmissionService.AnswerInput;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Validates scoped answer identities and JSON shapes against server-owned questions. */
@Component
public class RegistrationAnswerValidator {
  public void validate(List<QuestionDefinition> questions, List<AnswerInput> answers) {
    if (answers == null) {
      throw invalidAnswer();
    }
    Map<QuestionKey, QuestionDefinition> definitions = new HashMap<>();
    for (QuestionDefinition question : questions) {
      definitions.put(new QuestionKey(question.scope(), question.id()), question);
    }
    Set<QuestionKey> submitted = new HashSet<>();
    for (AnswerInput input : answers) {
      if (input == null) {
        throw invalidAnswer();
      }
      QuestionKey key = new QuestionKey(input.questionScope(), input.questionId());
      QuestionDefinition question = definitions.get(key);
      if (question == null) {
        throw semantic("UNKNOWN_QUESTION", "Answer does not match a question on this form");
      }
      if (!submitted.add(key)) {
        throw semantic("DUPLICATE_ANSWER", "A question can only be answered once");
      }
      validateValue(question, input.answer());
    }
    for (QuestionDefinition question : questions) {
      if (question.required()
          && !submitted.contains(new QuestionKey(question.scope(), question.id()))) {
        throw requiredMissing();
      }
    }
  }

  private void validateValue(QuestionDefinition question, JsonNode value) {
    boolean absent = value == null || value.isNull() || value.isMissingNode();
    boolean blankText =
        "TEXT".equals(question.type())
            && value != null
            && value.isTextual()
            && value.textValue().isBlank();
    boolean emptyChoices =
        "MULTIPLE_CHOICE".equals(question.type())
            && value != null
            && value.isArray()
            && value.isEmpty();
    if (question.required() && (absent || blankText || emptyChoices)) {
      throw requiredMissing();
    }
    if (absent) {
      throw invalidAnswer();
    }
    boolean valid =
        switch (question.type()) {
          case "TEXT" -> value.isTextual() && !value.textValue().isBlank();
          case "BOOLEAN" -> value.isBoolean();
          case "SINGLE_CHOICE" ->
              value.isTextual() && question.options().contains(value.textValue());
          case "MULTIPLE_CHOICE" -> validMultipleChoice(question.options(), value);
          default -> false;
        };
    if (!valid) {
      throw invalidAnswer();
    }
  }

  private boolean validMultipleChoice(List<String> options, JsonNode value) {
    if (!value.isArray() || value.isEmpty()) {
      return false;
    }
    Set<String> chosen = new HashSet<>();
    for (JsonNode option : value) {
      if (!option.isTextual()
          || !options.contains(option.textValue())
          || !chosen.add(option.textValue())) {
        return false;
      }
    }
    return true;
  }

  private BusinessException requiredMissing() {
    return semantic("REQUIRED_ANSWER_MISSING", "A required question must have an answer");
  }

  private BusinessException invalidAnswer() {
    return semantic("INVALID_ANSWER", "Answer has an invalid type or value");
  }

  private BusinessException semantic(String code, String message) {
    return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
  }

  private record QuestionKey(String scope, Long id) {}
}
