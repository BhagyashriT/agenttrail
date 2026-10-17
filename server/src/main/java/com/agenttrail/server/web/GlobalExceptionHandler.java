package com.agenttrail.server.web;

import com.agenttrail.server.dto.Dtos.ErrorResponse;
import com.agenttrail.server.service.ConflictException;
import com.agenttrail.server.service.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps exceptions to the contract's error shape: {"error":"<code>","message":"..."} + status. */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound(NotFoundException ex) {
    return status(HttpStatus.NOT_FOUND, "not_found", ex.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  public ResponseEntity<ErrorResponse> conflict(ConflictException ex) {
    return status(HttpStatus.CONFLICT, "conflict", ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex) {
    String detail =
        ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining("; "));
    return status(HttpStatus.BAD_REQUEST, "validation_error", detail.isEmpty() ? "invalid request" : detail);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> constraint(ConstraintViolationException ex) {
    return status(HttpStatus.BAD_REQUEST, "validation_error", ex.getMessage());
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  public ResponseEntity<ErrorResponse> badRequest(Exception ex) {
    return status(HttpStatus.BAD_REQUEST, "bad_request", "malformed request: " + ex.getMessage());
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> unexpected(Exception ex) {
    log.error("Unhandled exception", ex);
    return status(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "unexpected error");
  }

  private static ResponseEntity<ErrorResponse> status(HttpStatus s, String error, String message) {
    return ResponseEntity.status(s).body(new ErrorResponse(error, message));
  }
}
