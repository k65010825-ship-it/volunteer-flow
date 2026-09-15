package com.volunteerflow.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER_NAME = "X-Request-Id";
  public static final String ATTRIBUTE_NAME = "requestId";
  public static final String MDC_KEY = "requestId";

  private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._:-]{1,100}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = resolveRequestId(request.getHeader(HEADER_NAME));
    request.setAttribute(ATTRIBUTE_NAME, requestId);
    response.setHeader(HEADER_NAME, requestId);
    MDC.put(MDC_KEY, requestId);
    try {
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private String resolveRequestId(String incomingRequestId) {
    if (incomingRequestId != null && SAFE_REQUEST_ID.matcher(incomingRequestId).matches()) {
      return incomingRequestId;
    }
    return UUID.randomUUID().toString();
  }
}
