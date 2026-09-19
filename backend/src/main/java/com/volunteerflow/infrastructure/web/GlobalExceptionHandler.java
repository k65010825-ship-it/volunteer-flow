package com.volunteerflow.infrastructure.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ProblemDetail> handleBusiness(
      BusinessException exception, HttpServletRequest request) {
    return problem(exception.status(), exception.code(), exception.getMessage(), request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleValidation(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", request);
  }

  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<ProblemDetail> handleDuplicateKey(
      DuplicateKeyException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "REGISTRATION_CONFLICT",
        "Registration changed concurrently; refresh and retry",
        request);
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<ProblemDetail> handleConcurrentModification(
      OptimisticLockingFailureException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.CONFLICT,
        "CONCURRENT_MODIFICATION",
        "Resource changed concurrently; refresh and retry",
        request);
  }

  private ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String code, String detail, HttpServletRequest request) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setProperty("code", code);
    body.setProperty("requestId", request.getAttribute("requestId"));
    return ResponseEntity.status(status).body(body);
  }
}
