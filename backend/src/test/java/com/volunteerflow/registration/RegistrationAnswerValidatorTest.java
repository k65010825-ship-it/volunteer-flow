package com.volunteerflow.registration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.volunteerflow.activity.ActivityRegistrationPolicyService.QuestionDefinition;
import com.volunteerflow.infrastructure.web.BusinessException;
import com.volunteerflow.registration.RegistrationSubmissionService.AnswerInput;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RegistrationAnswerValidatorTest {
  private final RegistrationAnswerValidator validator = new RegistrationAnswerValidator();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void requiredQuestionMustHaveAnAnswer() {
    QuestionDefinition required = question("TEXT", true);
    rejects(List.of(required), List.of(), "REQUIRED_ANSWER_MISSING");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "TEXT|42",
        "TEXT|true",
        "TEXT|[]",
        "BOOLEAN|\"true\"",
        "BOOLEAN|1",
        "SINGLE_CHOICE|\"unknown\"",
        "SINGLE_CHOICE|[]",
        "MULTIPLE_CHOICE|\"A\"",
        "MULTIPLE_CHOICE|[\"A\",\"A\"]",
        "MULTIPLE_CHOICE|[\"unknown\"]",
        "MULTIPLE_CHOICE|[1]"
      })
  void rejectsWrongShapesAndInvalidOptions(String type, String value) throws Exception {
    rejects(List.of(question(type, true)), List.of(answer(value)), "INVALID_ANSWER");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"TEXT|\"   \"", "TEXT|null", "BOOLEAN|null", "MULTIPLE_CHOICE|[]"})
  void requiredEmptyValuesAreMissing(String type, String value) throws Exception {
    rejects(List.of(question(type, true)), List.of(answer(value)), "REQUIRED_ANSWER_MISSING");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "TEXT|\"经验\"",
        "BOOLEAN|false",
        "SINGLE_CHOICE|\"B\"",
        "MULTIPLE_CHOICE|[\"A\",\"B\"]"
      })
  void acceptsValidAnswersIncludingBooleanFalse(String type, String value) throws Exception {
    assertThatCode(() -> validator.validate(List.of(question(type, true)), List.of(answer(value))))
        .doesNotThrowAnyException();
  }

  @Test
  void optionalQuestionMayBeOmittedButSubmittedEmptyMultiChoiceIsInvalid() throws Exception {
    assertThatCode(() -> validator.validate(List.of(question("MULTIPLE_CHOICE", false)), List.of()))
        .doesNotThrowAnyException();
    rejects(List.of(question("MULTIPLE_CHOICE", false)), List.of(answer("[]")), "INVALID_ANSWER");
  }

  @Test
  void unknownScopeOrQuestionCannotInjectAnAnswer() throws Exception {
    rejects(
        List.of(question("TEXT", false)),
        List.of(new AnswerInput("POSITION", 1L, json.readTree("\"x\""))),
        "UNKNOWN_QUESTION");
    rejects(
        List.of(question("TEXT", false)),
        List.of(new AnswerInput("ACTIVITY", 99L, json.readTree("\"x\""))),
        "UNKNOWN_QUESTION");
  }

  @Test
  void duplicateQuestionAnswersAreRejected() throws Exception {
    rejects(
        List.of(question("TEXT", false)),
        List.of(answer("\"a\""), answer("\"b\"")),
        "DUPLICATE_ANSWER");
  }

  @Test
  void matchingNumericIdsAcrossScopesRemainDistinct() throws Exception {
    QuestionDefinition position =
        new QuestionDefinition("POSITION", 1L, "BOOLEAN", "确认", true, List.of(), 1);
    assertThatCode(
            () ->
                validator.validate(
                    List.of(question("TEXT", true), position),
                    List.of(
                        answer("\"a\""), new AnswerInput("POSITION", 1L, json.readTree("false")))))
        .doesNotThrowAnyException();
  }

  @Test
  void malformedNullInputsAreBusinessErrors() {
    rejects(List.of(), null, "INVALID_ANSWER");
    rejects(List.of(), Arrays.asList((AnswerInput) null), "INVALID_ANSWER");
    rejects(
        List.of(question("TEXT", true)),
        List.of(new AnswerInput("ACTIVITY", 1L, null)),
        "REQUIRED_ANSWER_MISSING");
  }

  private QuestionDefinition question(String type, boolean required) {
    return new QuestionDefinition("ACTIVITY", 1L, type, "特长", required, List.of("A", "B"), 1);
  }

  private AnswerInput answer(String value) throws Exception {
    JsonNode node = json.readTree(value);
    return new AnswerInput("ACTIVITY", 1L, node);
  }

  private void rejects(List<QuestionDefinition> questions, List<AnswerInput> answers, String code) {
    assertThatThrownBy(() -> validator.validate(questions, answers))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).code())
        .isEqualTo(code);
  }
}
