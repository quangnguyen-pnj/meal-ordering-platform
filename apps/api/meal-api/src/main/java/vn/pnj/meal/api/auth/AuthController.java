package vn.pnj.meal.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.pnj.meal.api.access.AllowPinChange;
import vn.pnj.meal.api.access.Authenticated;
import vn.pnj.meal.api.access.PrincipalService;
import vn.pnj.meal.api.access.PrincipalService.SessionUser;
import vn.pnj.meal.api.access.Public;
import vn.pnj.meal.api.access.RequestPrincipal;
import vn.pnj.meal.api.common.ApiException;
import vn.pnj.meal.api.db.Enums.AuthMethod;

@RestController
@RequestMapping("/auth")
public class AuthController {
  private final PinAuthService pinAuth;
  private final EntraAuthService entra;
  private final SessionService sessions;
  private final PrincipalService principals;

  public AuthController(
      PinAuthService pinAuth,
      EntraAuthService entra,
      SessionService sessions,
      PrincipalService principals) {
    this.pinAuth = pinAuth;
    this.entra = entra;
    this.sessions = sessions;
    this.principals = principals;
  }

  public record PinLoginBody(
      @NotNull @Size(min = 1, max = 32) String employeeCode,
      @NotNull @Pattern(regexp = "\\d{6}", message = "PIN must be exactly 6 digits") String pin) {
    public PinLoginBody {
      employeeCode = employeeCode == null ? null : employeeCode.trim();
    }
  }

  public record ChangePinBody(
      @NotNull @Pattern(regexp = "\\d{6}", message = "PIN must be exactly 6 digits")
          String currentPin,
      @NotNull @Pattern(regexp = "\\d{6}", message = "PIN must be exactly 6 digits")
          String newPin) {}

  public record UserBody(SessionUser user) {}

  @Public
  @PostMapping("/pin/login")
  ResponseEntity<UserBody> pinLogin(
      @Valid @RequestBody PinLoginBody body,
      @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
    PinAuthService.SignedIn signedIn = pinAuth.login(body.employeeCode(), body.pin(), userAgent);
    RequestPrincipal principal =
        principals.load(signedIn.employeeId(), signedIn.session().id(), AuthMethod.PIN);
    HttpHeaders headers = new HttpHeaders();
    sessions.setCookie(headers, signedIn.session().token(), signedIn.session().expiresAt());
    return ResponseEntity.ok()
        .headers(headers)
        .body(new UserBody(principal == null ? null : principals.toSessionUser(principal)));
  }

  @Authenticated
  @AllowPinChange
  @PostMapping("/pin/change")
  ResponseEntity<Void> changePin(
      @Valid @RequestBody ChangePinBody body, RequestPrincipal principal) {
    if (body.currentPin().equals(body.newPin())) {
      throw ApiException.invalid(
          "Validation failed",
          List.of(Map.of("path", "newPin", "message", "New PIN must differ from the current PIN")));
    }
    pinAuth.changePin(
        principal.employeeId(), principal.sessionId(), body.currentPin(), body.newPin());
    return ResponseEntity.noContent().build();
  }

  @Authenticated
  @AllowPinChange
  @PostMapping("/logout")
  ResponseEntity<Void> logout(RequestPrincipal principal) {
    sessions.revoke(principal.sessionId());
    HttpHeaders headers = new HttpHeaders();
    sessions.clearCookie(headers);
    return ResponseEntity.noContent().headers(headers).build();
  }

  @Authenticated
  @AllowPinChange
  @GetMapping("/me")
  UserBody me(RequestPrincipal principal) {
    return new UserBody(principals.toSessionUser(principal));
  }

  // --- Entra ID SSO (plan section 5) -----------------------------------------------------------

  @Public
  @GetMapping("/entra/login")
  ResponseEntity<Void> entraLogin() {
    EntraAuthService.Started started = entra.start();
    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(started.redirectUrl()))
        .header(
            HttpHeaders.SET_COOKIE,
            txCookie(started.cookieValue(), EntraAuthService.TX_TTL_MILLIS / 1000).toString())
        .build();
  }

  @Public
  @GetMapping("/entra/callback")
  ResponseEntity<Void> entraCallback(
      @RequestParam Map<String, String> query,
      @CookieValue(value = EntraAuthService.TX_COOKIE, required = false) String txCookie,
      HttpServletRequest request) {
    EntraAuthService.Result result =
        entra.complete(query, txCookie, request.getHeader(HttpHeaders.USER_AGENT));
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.SET_COOKIE, txCookie("", 0).toString());
    String target =
        switch (result) {
          case EntraAuthService.Result.Ok ok -> {
            sessions.setCookie(headers, ok.session().token(), ok.session().expiresAt());
            yield "/today";
          }
          case EntraAuthService.Result.NotLinked notLinked -> "/auth/not-linked";
          case EntraAuthService.Result.Failed failed -> "/login?error=sso_failed";
        };
    return ResponseEntity.status(HttpStatus.FOUND)
        .headers(headers)
        .location(URI.create(target))
        .build();
  }

  /** The fake "Microsoft" page. Exists only in ENTRA_MODE=mock; 404 otherwise. */
  @Public
  @GetMapping("/entra/mock-authorize")
  ResponseEntity<String> mockAuthorize(@RequestParam(required = false) String state) {
    if (!entra.provider().isMock()) {
      throw ApiException.notFound("Not found");
    }
    String safeState = state == null ? "" : state.replaceAll("[^A-Za-z0-9_-]", "");
    String html =
        """
        <!doctype html>
        <html lang="vi"><head><meta charset="utf-8"><title>Mock Microsoft sign-in</title></head>
        <body style="font-family:sans-serif;max-width:360px;margin:4rem auto">
        <h1>Mock Microsoft sign-in</h1>
        <p>Development only. Enter the email of the employee to sign in as.</p>
        <form method="get" action="/api/auth/entra/callback">
        <input type="hidden" name="state" value="%s">
        <input type="email" name="mock_email" placeholder="an.nguyen@pnjp.test" required style="width:100%%;padding:.5rem">
        <button type="submit" style="margin-top:1rem;padding:.5rem 1rem">Sign in</button>
        </form></body></html>"""
            .formatted(safeState);
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
        .body(html);
  }

  private ResponseCookie txCookie(String value, long maxAgeSeconds) {
    return ResponseCookie.from(EntraAuthService.TX_COOKIE, value)
        .httpOnly(true)
        .sameSite("Lax") // the return from Microsoft is a cross-site top-level GET
        .secure(sessions.secureCookies())
        .path(EntraAuthService.TX_COOKIE_PATH)
        .maxAge(maxAgeSeconds)
        .build();
  }
}
