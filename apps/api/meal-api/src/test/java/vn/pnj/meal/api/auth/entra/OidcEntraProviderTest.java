package vn.pnj.meal.api.auth.entra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.pkce.CodeChallenge;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.SubjectType;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The real OIDC flow (Authorization Code + PKCE) against a local fake identity provider: the
 * provider checks the PKCE verifier and signs the id_token; the client must check the signature,
 * issuer, audience, nonce and state.
 */
@DisplayName("Entra OIDC provider")
class OidcEntraProviderTest {
  private static final String CLIENT = "meal-client";
  private static final String SECRET = "meal-secret";
  private static final String REDIRECT = "https://meal.example.test/api/auth/entra/callback";

  private HttpServer server;
  private String base;
  private RSAKey signingKey;
  private RSAKey otherKey;
  private OidcEntraProvider provider;

  /** What the fake provider puts into the next id_token. */
  private String issuedNonce;

  private RSAKey issuedWith;
  private String tokenError;
  private String expectedChallenge;
  private String issuedEmail = "An.Nguyen@PNJP.test";

  @BeforeEach
  void startProvider() throws Exception {
    signingKey = new RSAKeyGenerator(2048).keyID("k1").generate();
    otherKey = new RSAKeyGenerator(2048).keyID("k1").generate();
    issuedWith = signingKey;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/jwks", ex -> reply(ex, 200, new JWKSet(signingKey.toPublicJWK()).toString()));
    server.createContext("/token", ex -> reply(ex, tokenError == null ? 200 : 400, token(ex)));
    server.start();

    OIDCProviderMetadata metadata =
        new OIDCProviderMetadata(
            new Issuer(base), List.of(SubjectType.PUBLIC), URI.create(base + "/jwks"));
    metadata.setAuthorizationEndpointURI(URI.create(base + "/authorize"));
    metadata.setTokenEndpointURI(URI.create(base + "/token"));
    provider = new OidcEntraProvider(() -> metadata, CLIENT, SECRET, REDIRECT);
  }

  @AfterEach
  void stopProvider() {
    server.stop(0);
  }

  private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(status, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }

  private String token(com.sun.net.httpserver.HttpExchange ex) throws IOException {
    if (tokenError != null) {
      return "{\"error\":\"" + tokenError + "\"}";
    }
    Map<String, String> form = new HashMap<>();
    for (String pair :
        new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
      String[] kv = pair.split("=", 2);
      form.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
    }
    String challenge =
        CodeChallenge.compute(CodeChallengeMethod.S256, new CodeVerifier(form.get("code_verifier")))
            .getValue();
    if (!challenge.equals(expectedChallenge)
        || !CLIENT.equals(form.get("client_id"))
        || !SECRET.equals(form.get("client_secret"))
        || !REDIRECT.equals(form.get("redirect_uri"))
        || !"the-code".equals(form.get("code"))) {
      return "{\"error\":\"invalid_grant\"}";
    }
    try {
      JWTClaimsSet claims =
          new JWTClaimsSet.Builder()
              .issuer(base)
              .audience(CLIENT)
              .subject("user-1")
              .issueTime(new Date())
              .expirationTime(new Date(System.currentTimeMillis() + 600_000))
              .claim("nonce", issuedNonce)
              .claim("email", issuedEmail)
              .build();
      SignedJWT jwt =
          new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("k1").build(), claims);
      jwt.sign(new RSASSASigner(issuedWith));
      return "{\"access_token\":\"a\",\"token_type\":\"Bearer\",\"expires_in\":600,\"id_token\":\""
          + jwt.serialize()
          + "\"}";
    } catch (JOSEException e) {
      throw new IOException(e);
    }
  }

  /** Starts a sign-in and plays the browser and Microsoft up to the callback query. */
  private Map<String, String> authorize(EntraProvider.Start start) {
    var query = UriComponentsBuilder.fromUriString(start.redirectUrl()).build().getQueryParams();
    assertThat(query.getFirst("code_challenge_method")).isEqualTo("S256");
    assertThat(URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8))
        .isEqualTo(REDIRECT);
    expectedChallenge = query.getFirst("code_challenge");
    issuedNonce = start.transaction().nonce();
    return Map.of("code", "the-code", "state", start.transaction().state());
  }

  @Test
  @DisplayName("completes Authorization Code + PKCE and returns the email claim")
  void happyPath() throws Exception {
    EntraProvider.Start start = provider.start();
    assertThat(start.redirectUrl()).startsWith(base + "/authorize?");
    EntraProvider.Claims claims = provider.complete(authorize(start), start.transaction());
    assertThat(claims.email()).isEqualTo("An.Nguyen@PNJP.test");
  }

  @Test
  @DisplayName("rejects a callback whose state is not the one sent")
  void wrongState() {
    EntraProvider.Start start = provider.start();
    Map<String, String> query = new HashMap<>(authorize(start));
    query.put("state", "forged");
    assertThatThrownBy(() -> provider.complete(query, start.transaction()))
        .hasMessageContaining("State");
  }

  @Test
  @DisplayName("rejects an id_token with another nonce (replay)")
  void wrongNonce() {
    EntraProvider.Start start = provider.start();
    Map<String, String> query = authorize(start);
    issuedNonce = "someone-elses-nonce";
    assertThatThrownBy(() -> provider.complete(query, start.transaction()))
        .hasMessageContaining("nonce");
  }

  @Test
  @DisplayName("rejects an id_token not signed by the tenant's key")
  void wrongKey() {
    EntraProvider.Start start = provider.start();
    Map<String, String> query = authorize(start);
    issuedWith = otherKey;
    assertThatThrownBy(() -> provider.complete(query, start.transaction()))
        .isInstanceOf(Exception.class);
  }

  @Test
  @DisplayName("reports a refused code exchange")
  void tokenError() {
    EntraProvider.Start start = provider.start();
    Map<String, String> query = authorize(start);
    tokenError = "invalid_grant";
    assertThatThrownBy(() -> provider.complete(query, start.transaction()))
        .hasMessageContaining("invalid_grant");
  }
}
