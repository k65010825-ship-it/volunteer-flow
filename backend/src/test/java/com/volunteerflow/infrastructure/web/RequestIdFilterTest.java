package com.volunteerflow.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();

  @Test
  void keepsValidIncomingRequestIdAndClearsMdc() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestIdFilter.HEADER_NAME, "request-123");
    MockHttpServletResponse response = new MockHttpServletResponse();

    FilterChain chain =
        (servletRequest, servletResponse) -> {
          assertThat(servletRequest.getAttribute(RequestIdFilter.ATTRIBUTE_NAME))
              .isEqualTo("request-123");
          assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("request-123");
        };

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("request-123");
    assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void generatesRequestIdWhenHeaderIsMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (servletRequest, servletResponse) -> {});

    assertThat(response.getHeader(RequestIdFilter.HEADER_NAME))
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  }
}
