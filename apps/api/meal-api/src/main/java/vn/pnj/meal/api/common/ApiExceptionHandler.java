package vn.pnj.meal.api.common;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as an {@link ApiErrorBody}. Unexpected exceptions are logged and
 * answered with a bare 500, so no stack trace or SQL reaches the browser.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<ApiErrorBody> handleApi(ApiException e) {
    return ResponseEntity.status(e.status()).body(e.body());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiErrorBody> handleUnexpected(Exception e) {
    log.error("Unhandled exception", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiErrorBody.of(500, ApiErrorCode.INTERNAL, "Internal server error"));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<Map<String, String>> issues =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f -> Map.of("path", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
            .toList();
    return ResponseEntity.badRequest()
        .body(
            new ApiErrorBody(
                400, ApiErrorCode.VALIDATION_FAILED, "Validation failed", issues, null));
  }

  /** Like the legacy API, an unknown method on a known path is simply "not found". */
  @Override
  protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
      HttpRequestMethodNotSupportedException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiErrorBody.of(404, ApiErrorCode.NOT_FOUND, "Not found"));
  }

  /** Every other Spring MVC error (bad JSON, missing parameter, unknown route...). */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return ResponseEntity.status(status).body(ErrorBodies.forStatus(status.value()));
  }
}
