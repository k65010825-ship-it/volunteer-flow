package com.volunteerflow.infrastructure.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.volunteerflow.infrastructure.ratelimit.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerRateLimitTest {

  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void rateLimitResponseContainsProblemCodeRequestIdAndRetryAfter() throws Exception {
    mvc.perform(post("/probe/rate-limit").requestAttr("requestId", "rate-request-1"))
        .andExpect(status().isTooManyRequests())
        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "8"))
        .andExpect(jsonPath("$.status").value(429))
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
        .andExpect(jsonPath("$.requestId").value("rate-request-1"));
  }

  @Test
  void retryAfterIsClampedToAtLeastOneWithoutSubjectDetails() throws Exception {
    mvc.perform(post("/probe/rate-limit-clamped").requestAttr("requestId", "rate-request-2"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
        .andExpect(content().string(not(containsString("SensitiveUser"))))
        .andExpect(content().string(not(containsString("203.0.113.91"))))
        .andExpect(content().string(not(containsString("981723"))));
  }

  @RestController
  static class ProbeController {

    @PostMapping("/probe/rate-limit")
    void rateLimit() {
      throw new RateLimitExceededException(8);
    }

    @PostMapping("/probe/rate-limit-clamped")
    void rateLimitClamped() {
      throw new RateLimitExceededException(0);
    }
  }
}
