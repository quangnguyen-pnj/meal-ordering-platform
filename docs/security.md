# Security checklist

The API is Java (`apps/api`) since J3; checks name its tests (`apps/api/meal-api/src/test/...`) and the web's (`apps/web`). Run through this list before closing any phase or change request (AGENTS.md rule 14), and add to it when a review finds something new. Each item names the check that guards it, if there is one.

## Credentials (PIN, session, secrets)

- [ ] **The PIN only travels in a POST body over HTTPS.** Never in a URL, a query string, a header we log, or a GET form. Forms with a PIN input use `method="post"`, so a submit before hydration does not fall back to GET (`apps/web/src/components/forms.test.tsx`).
- [ ] **Production runs on HTTPS only.** The api refuses to start in production without `COOKIE_SECURE=true` (`AppPropertiesTest`, `ProductionSafetyTest`). TLS terminates at nginx or at the load balancer in front of it (`docker/nginx.conf`).
- [ ] **No response carries a PIN hash, a token hash or a configured secret.** Read only the fields you return (`select` or a mapped view), never a whole `Employee`/`AuthSession` row (`SecretsTest`; add every new read endpoint to its `READS`, the P2 registration reads included).
- [ ] **Errors do not echo what was sent.** Validation errors give the path and a message, never the received value (`HttpBasicsTest`).
- [ ] **Unexpected errors reveal nothing.** Anything the API did not throw on purpose answers a bare 500 `INTERNAL`; the exception and its message (which may hold SQL or data) go to the server log only (Java: `ApiExceptionHandler`, `HttpBasicsTest`).
- [ ] **Timestamps are UTC whatever the JVM zone.** `JdbcClient` code converts through `SqlTime`, Hibernate through `hibernate.jdbc.time_zone=UTC`, and the Java tests run in Asia/Ho_Chi_Minh, so a lock or a session expiry is never off by seven hours.
- [ ] **Configuration never prints a secret.** `AppProperties` and `DatabaseUrl` leave the session secret, the Entra client secret, the DMP password and the database password out of `toString()`, so a startup log or an error page cannot show them (`AppPropertiesTest`, `DatabaseUrlTest`). New configuration records with a secret follow the same pattern.
- [ ] **Secrets never go into `AuditLog` before/after, logs or exceptions.** The temporary PIN from a reset is returned once to the admin and is not stored or audited in clear.
- [ ] **No API answer is cached:** `Cache-Control: no-store`, `nosniff`, no `X-Powered-By` (`SecurityHeadersFilter`, `HttpBasicsTest`, `SecretsTest`).
- [ ] **The session token stays in the httpOnly cookie,** never in a response body, `localStorage` or a URL (`AuthFlowTest`).
- [ ] **Nothing secret is committed;** `.env.example` holds placeholders only.

## Access

- [ ] Every endpoint has an explicit `@Public`, `@Authenticated` or `@RequirePermission` (the interceptor denies a route without one), and every admin endpoint has a 403 test for out-of-scope access (`AccessControlTest` checks every route declares a rule; `OrgTest`, `MenuTest`, `AdminSettingsTest` and `RegistrationTest` sweep the P1 and P2 endpoints with employee, other-workshop dept-admin and, where the matrix excludes them, system-admin sessions).
- [ ] Ids taken from the URL or body are checked against the caller's scope (factory, org unit, canteen), not only against existence. Every service loads the row and compares its `factoryId` with the caller's before acting; another factory's id answers 404 like a missing one. The bulk grid and its Excel import also check every employee code in the body against the org node of the URL (`NOT_IN_UNIT`), so a dept admin cannot write meals for someone outside their units by naming them (`RegistrationTest`).
- [ ] **Cached data in the browser is per user.** A PIN sign-in drops every cached query, and the banners key holds the user id, so a second person signing in in the same tab does not see the first person's data (`apps/web/src/components/forms.test.tsx`, `banner-area.test.tsx`). Microsoft sign-in is a full page load, which starts with an empty cache.
- [ ] **The Microsoft transaction cookie cannot be forged or replayed late:** HMAC-SHA256 with `SESSION_SECRET`, compared in constant time, 10-minute expiry judged by `ClockService` (`EntraSignInTest`). The id_token is checked for signature, issuer, audience and nonce (`OidcEntraProviderTest`).
- [ ] **Wrong PINs are counted even though the request fails** (the transaction does not roll back on the 401), so the lock after `pin.maxAttempts` holds (`AuthFlowTest`).
- [ ] Development-only routes (fake clock, mock Microsoft page, `/api/openapi.json`) are not mounted in production (`AppProperties` refuses the settings; the fake clock route only exists under `FakeTimeAllowedCondition`, the API description only with `API_DOCS_ENABLED=true`; an unknown `APP_ENV`/`NODE_ENV` also stops the Java API, so it never counts as "not production").

## Input and output

- [ ] Every body and query is validated on the server with Jakarta Bean Validation (`@Valid` on record DTOs) or `JsonBody` for PATCH bodies. A number that must be whole is read as a double and checked, so 1.5 is refused instead of being truncated to 1 (`RegistrationTest`: quantities, extra meals).
- [ ] Uploads have a size and row limit and are parsed, never executed; a file that is not the template is rejected. 5 MB per file (`spring.servlet.multipart`), 20,000 mapping rows, 2,000 library rows and 2,000 registration rows, `.xlsx` only through Apache POI, whose XML parsing has external entities off and whose zip reader refuses archives that inflate suspiciously (`OrgTest`, `MenuTest`, `RegistrationTest` "not the template").
- [ ] Spreadsheet exports write user-entered text as text cells, never as formulas; a CSV export escapes a leading `=`, `+`, `-` or `@` (formula injection). `Xlsx.addRow` only calls `setCellValue(String)`, which POI stores as a string even when it starts with `=`; the registration template writes quantities as numbers and names as strings.
- [ ] The web app cannot be framed (`X-Frame-Options: DENY`, `frame-ancestors 'none'` in `apps/web/next.config.ts`).

## What we decided not to do

- **No hashing or encrypting the PIN in the browser.** Seeing your own PIN in the request payload in DevTools is expected: DevTools shows what the browser sends before TLS encrypts it, and only someone already at that browser can see it. A hash made in the browser would become the password (anyone who captures it can replay it), and a 6-digit PIN has only a million values, so any hash or challenge response captured from the network can be brute-forced in seconds. HTTPS is what protects the PIN in transit; the server stores only an argon2id hash and locks the account after `pin.maxAttempts` wrong tries.

## Open follow-ups

- A full Content-Security-Policy (scripts and styles) for the web app.
- Per-IP rate limiting on `/api/auth/pin/login`, in addition to the per-account lock.

## How to check (review of 2026-10-24)

```bash
cd apps/api && ./mvnw verify                        # SecretsTest (P0-P2 reads), AccessControlTest, AuthFlowTest, AppPropertiesTest, HttpBasicsTest
pnpm --filter @meal/web test -- forms               # PIN forms post natively
curl -sI localhost:3001/api/auth/me                 # cache-control: no-store, nosniff
```

In the browser: DevTools → Network → disable JavaScript, sign in, and check the URL stays `/login` with no `pin=`.
