# Agent rules – PNJP Meal Ordering System

Source of truth: `docs/plan.md` (MVP Plan v1.2, aligned with BRQ-MEAL-001 v1.0).

1. Read `docs/plan.md` before each phase. Do exactly one phase per run; never start work belonging to a later phase.
2. Business rules are pure Java functions in `apps/api/meal-rules` (no Spring, no database); test names cite the BR code with `@DisplayName`, e.g. `"BR-23: an employee with no forecast can add a meal at 07:59 on day D"`.
3. Never read the wall clock in business code (`Instant.now()`, `LocalDate.now()`, `new Date()`, `System.currentTimeMillis()`…): use `ClockService` in the api and pass `now` into rule functions. Read every deadline from `Setting`; never hardcode. (ArchUnit enforces this in Java, ESLint in TypeScript.)
4. Store timestamps in UTC; convert to Asia/Ho_Chi_Minh in rules and UI. Store serving days as `date`.
5. Check permissions in the guard as (role, action, scope). Every admin endpoint has a 403 test for out-of-scope access.
6. Every change after a lock requires a reason and writes an `AuditLog` entry.
7. Never delete business data; use statuses or `archivedAt`. `ForecastLine`, `FinalEmployeeLine` and `FinalCount` are write-once; changes after finalization go through `Adjustment` (BR-46).
8. Call DMP only through `DmpClient`. Dev and tests use `DMP_MODE=mock`; never call the real DMP in tests; never call `generate-data`.
9. UI copy is Vietnamese, kept in one messages file (`apps/web/src/messages/vi.ts`) for easy editing.
10. When the spec is unclear: log it in `docs/open-questions.md`, take the safest option already in the plan, mark a `TODO(BR-xx)`; never invent new rules.
11. Closing a phase: lint, typecheck and tests pass; seed runs; `docs/decisions.md` is updated; write how to demo each exit criterion (`docs/demo-pN.md`).
12. Never commit secrets; `.env.example` lists every variable.
13. The API is Java (`apps/api`; the NestJS API was removed in J3). Database changes are Flyway migrations in `apps/api/meal-api/src/main/resources/db/migration`. The web's API types come from the API: after changing a request or response, run `pnpm --filter @meal/api openapi` and `pnpm --filter @meal/web api:types` and commit both generated files (tests fail while they are stale). A response record component that can be null is marked `@Nullable` (JSpecify); a string with fixed values gets `@Schema(allowableValues = ...)`.
14. Security is part of every change: before closing a phase or change request, go through `docs/security.md` for what you touched (no PIN or secret in a URL, a response, a log or the audit; HTTPS in production; scope checks on ids). Add every new finding to that file with the test that guards it.

## Commands

Needs Node >= 22.12, pnpm 9 and a JDK 25 (`JAVA_HOME`). Maven comes with the wrapper (`apps/api/mvnw`).

```bash
pnpm install
pnpm lint && pnpm typecheck && pnpm test   # all via turbo, Java included
cd apps/api && ./mvnw verify               # the Java API alone (Spotless check, tests)
cd apps/api && ./mvnw spotless:apply       # format Java code
pnpm db:local                              # PostgreSQL without Docker on :5433 (keep running)
pnpm db:seed                               # Flyway migrations + demo data
pnpm dev                                   # Java API :3001 and web :3000
pnpm --filter @meal/api openapi            # after an API change: rewrite apps/api/openapi.json
pnpm --filter @meal/web api:types          # then regenerate apps/web/src/lib/openapi.d.ts
```
