package vn.pnj.meal.api.auth.entra;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWT;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.ErrorObject;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.auth.ClientSecretPost;
import com.nimbusds.oauth2.sdk.auth.Secret;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.openid.connect.sdk.AuthenticationRequest;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse;
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import com.nimbusds.openid.connect.sdk.validators.IDTokenValidator;
import java.net.URI;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Real Entra ID flow: OIDC Authorization Code + PKCE (plan section 6.1), with the Nimbus OAuth 2.0
 * SDK (the library under Spring Security's OAuth2 client). The id_token is checked against the
 * tenant's keys, issuer, audience and nonce.
 *
 * <p>TODO: untested against a real tenant. The Entra app registration is an external dependency
 * (plan section 8, before P7); until then ENTRA_MODE=mock is used. {@code OidcEntraProviderTest}
 * runs the whole flow against a local fake provider.
 */
public class OidcEntraProvider implements EntraProvider {
  private final Supplier<OIDCProviderMetadata> metadata;
  private final ClientID clientId;
  private final Secret clientSecret;
  private final URI redirectUri;
  private volatile OIDCProviderMetadata resolved;

  public OidcEntraProvider(
      Supplier<OIDCProviderMetadata> metadata,
      String clientId,
      String clientSecret,
      String redirectUri) {
    this.metadata = metadata;
    this.clientId = new ClientID(clientId);
    this.clientSecret = new Secret(clientSecret);
    this.redirectUri = URI.create(redirectUri);
  }

  /** Discovery from Entra's well-known document for the tenant. */
  public static OidcEntraProvider forTenant(
      String tenantId, String clientId, String clientSecret, String redirectUri) {
    Issuer issuer = new Issuer("https://login.microsoftonline.com/" + tenantId + "/v2.0");
    return new OidcEntraProvider(
        () -> {
          try {
            return OIDCProviderMetadata.resolve(issuer);
          } catch (Exception e) {
            throw new IllegalStateException("Entra discovery failed", e);
          }
        },
        clientId,
        clientSecret,
        redirectUri);
  }

  private OIDCProviderMetadata metadata() {
    if (resolved == null) {
      resolved = metadata.get();
    }
    return resolved;
  }

  @Override
  public boolean isMock() {
    return false;
  }

  @Override
  public Start start() {
    CodeVerifier verifier = new CodeVerifier();
    State state = new State();
    Nonce nonce = new Nonce();
    URI url =
        new AuthenticationRequest.Builder(
                ResponseType.CODE, new Scope("openid", "profile", "email"), clientId, redirectUri)
            .endpointURI(metadata().getAuthorizationEndpointURI())
            .state(state)
            .nonce(nonce)
            .codeChallenge(verifier, CodeChallengeMethod.S256)
            .build()
            .toURI();
    return new Start(
        url.toString(), new Transaction(state.getValue(), nonce.getValue(), verifier.getValue()));
  }

  @Override
  public Claims complete(Map<String, String> query, Transaction tx) throws Exception {
    if (query.get("error") != null) {
      throw new IllegalStateException("Entra returned an error: " + query.get("error"));
    }
    if (!tx.state().equals(query.get("state"))) {
      throw new IllegalStateException("State mismatch");
    }
    String code = query.get("code");
    if (code == null) {
      throw new IllegalStateException("No authorization code");
    }
    OIDCProviderMetadata meta = metadata();
    TokenRequest request =
        new TokenRequest(
            meta.getTokenEndpointURI(),
            new ClientSecretPost(clientId, clientSecret),
            new AuthorizationCodeGrant(
                new AuthorizationCode(code), redirectUri, new CodeVerifier(tx.codeVerifier())));
    var response = OIDCTokenResponseParser.parse(request.toHTTPRequest().send());
    if (!response.indicatesSuccess()) {
      ErrorObject error = response.toErrorResponse().getErrorObject();
      throw new IllegalStateException("Entra token request failed: " + error.getCode());
    }
    JWT idToken = ((OIDCTokenResponse) response.toSuccessResponse()).getOIDCTokens().getIDToken();
    if (idToken == null) {
      throw new IllegalStateException("Entra response contained no id_token");
    }
    IDTokenClaimsSet claims =
        new IDTokenValidator(
                meta.getIssuer(), clientId, JWSAlgorithm.RS256, meta.getJWKSetURI().toURL())
            .validate(idToken, new Nonce(tx.nonce()));
    return new Claims(claims.getClaim("email"), claims.getClaim("preferred_username"));
  }
}
