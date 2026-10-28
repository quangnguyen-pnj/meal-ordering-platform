package vn.pnj.meal.api.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Error response, the same shape as {@code ApiErrorSchema} in the shared package. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorBody(
    int statusCode, ApiErrorCode code, String message, Object details, String lockedUntil) {

  public static ApiErrorBody of(int statusCode, ApiErrorCode code, String message) {
    return new ApiErrorBody(statusCode, code, message, null, null);
  }
}
