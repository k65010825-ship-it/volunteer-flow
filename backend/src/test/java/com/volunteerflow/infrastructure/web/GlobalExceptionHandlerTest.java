package com.volunteerflow.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void businessExceptionReturnsStableCodeAndRequestId() throws Exception {
        mvc.perform(post("/probe/business")
                        .requestAttr("requestId", "test-request-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POSITION_FULL"))
                .andExpect(jsonPath("$.requestId").value("test-request-1"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void invalidBodyReturnsValidationError() throws Exception {
        mvc.perform(post("/probe/validation")
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
            throw new BusinessException(org.springframework.http.HttpStatus.CONFLICT,
                    "POSITION_FULL", "Position is full");
        }

        @PostMapping("/probe/validation")
        void validation(@Valid @RequestBody ProbeRequest request) {
        }
    }

    record ProbeRequest(@NotBlank String name) {
    }
}
