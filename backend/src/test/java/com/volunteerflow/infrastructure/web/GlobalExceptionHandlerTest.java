package com.volunteerflow.infrastructure.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void businessExceptionReturnsStableCodeAndRequestId() throws Exception {
    mvc.perform(post("/probe/business").requestAttr("requestId", "test-request-1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("POSITION_FULL"))
        .andExpect(jsonPath("$.requestId").value("test-request-1"))
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void invalidBodyReturnsValidationError() throws Exception {
    mvc.perform(
            post("/probe/validation")
                .requestAttr("requestId", "test-request-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.requestId").value("test-request-2"));
  }

  @RestController
  static class ProbeController {

    @PostMapping("/probe/business")
    void business() {
      throw new BusinessException(
          org.springframework.http.HttpStatus.CONFLICT, "POSITION_FULL", "Position is full");
    }

    @PostMapping("/probe/validation")
    void validation(@Valid @RequestBody ProbeRequest request) {}

    @PostMapping("/probe/duplicate")
    void duplicate() {
      throw new DuplicateKeyException("INSERT INTO secret_table password=secret");
    }

    @PostMapping("/probe/concurrent")
    void concurrent() {
      throw new OptimisticLockingFailureException("UPDATE secret_table password=secret");
    }
  }

  @Test
  void duplicateKeyReturnsSafeConflict() throws Exception {
    mvc.perform(post("/probe/duplicate").requestAttr("requestId", "duplicate-request"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("REGISTRATION_CONFLICT"))
        .andExpect(jsonPath("$.requestId").value("duplicate-request"))
        .andExpect(content().string(not(containsString("secret"))));
  }

  @Test
  void optimisticLockReturnsSafeConflict() throws Exception {
    mvc.perform(post("/probe/concurrent").requestAttr("requestId", "concurrent-request"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
        .andExpect(jsonPath("$.requestId").value("concurrent-request"))
        .andExpect(content().string(not(containsString("secret"))));
  }

  record ProbeRequest(@NotBlank String name) {}
}
