package vn.pnj.meal.api.common;

import org.springframework.http.HttpStatus;

/** Default error bodies for errors that carry only an HTTP status. */
final class ErrorBodies {
  private ErrorBodies() {}

  static ApiErrorBody forStatus(int status) {
    ApiErrorCode code =
        switch (status) {
          case 400 -> ApiErrorCode.VALIDATION_FAILED;
          case 401 -> ApiErrorCode.UNAUTHENTICATED;
          case 403 -> ApiErrorCode.FORBIDDEN;
          case 404, 405 -> ApiErrorCode.NOT_FOUND;
          default -> ApiErrorCode.INTERNAL;
        };
    int answered = status == 405 ? 404 : status;
    HttpStatus resolved = HttpStatus.resolve(answered);
    String message = resolved != null ? resolved.getReasonPhrase() : "Error";
    return ApiErrorBody.of(answered, code, message);
  }
}
