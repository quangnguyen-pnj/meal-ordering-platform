package vn.pnj.meal.api.common;

import org.springframework.http.HttpStatus;

/** An error the API answers on purpose, with a code the web turns into Vietnamese copy. */
public class ApiException extends RuntimeException {
  private final HttpStatus status;
  private final ApiErrorCode code;
  private final Object details;
  private final String lockedUntil;

  public ApiException(HttpStatus status, ApiErrorCode code, String message) {
    this(status, code, message, null, null);
  }

  public ApiException(
      HttpStatus status, ApiErrorCode code, String message, Object details, String lockedUntil) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
    this.lockedUntil = lockedUntil;
  }

  public HttpStatus status() {
    return status;
  }

  public ApiErrorBody body() {
    return new ApiErrorBody(status.value(), code, getMessage(), details, lockedUntil);
  }

  public static ApiException notFound(String what) {
    return new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, what);
  }

  public static ApiException forbidden(String message) {
    return new ApiException(HttpStatus.FORBIDDEN, ApiErrorCode.FORBIDDEN, message);
  }

  public static ApiException invalid(String message, Object details) {
    return new ApiException(
        HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, message, details, null);
  }

  public static ApiException conflict(String message, Object details) {
    return new ApiException(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, message, details, null);
  }

  public static ApiException invalidState(String message, Object details) {
    return new ApiException(
        HttpStatus.CONFLICT, ApiErrorCode.INVALID_STATE, message, details, null);
  }
}
