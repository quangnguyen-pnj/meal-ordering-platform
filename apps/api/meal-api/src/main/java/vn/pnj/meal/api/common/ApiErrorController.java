package vn.pnj.meal.api.common;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Errors raised outside Spring MVC (in a servlet filter, or by the container) are forwarded to
 * {@code /error}; this answers them in the same {@link ApiErrorBody} shape instead of Spring's
 * default page.
 */
@RestController
public class ApiErrorController implements ErrorController {

  @RequestMapping("/error")
  ResponseEntity<ApiErrorBody> error(HttpServletRequest request) {
    Object status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
    int code = status instanceof Integer value ? value : 500;
    ApiErrorBody body = ErrorBodies.forStatus(code);
    return ResponseEntity.status(body.statusCode()).body(body);
  }
}
