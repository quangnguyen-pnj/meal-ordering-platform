# Demo – J1, P0 on Java

The exit criteria of J1 (plan, "J0–J3 – Java migration") and how to see each one. Needs a JDK 25 in `JAVA_HOME`.

## The P0 exit criteria hold on the Java API

| P0 exit criterion                                                      | Automated in `apps/api`                                                                                                                  |
| ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| All 4 roles can sign in                                                | `AuthFlowTest` "all 4 roles can sign in"                                                                                                 |
| 403 on out-of-scope access                                             | `AccessControlTest` (PIN reset by scope, lists by scope, role-level actions, fail-closed guard), `SystemRolesTest`, `EmployeesAdminTest` |
| Fake time at Thursday 23:59:30 and Friday 00:00 give different results | `ClockServiceTest` "BR-03", `DeadlinesTest`                                                                                              |
| Mapping resolution on the 6-level seed tree                            | `MappingTest`, `SeedDataTest`                                                                                                            |
| The whole system runs                                                  | `HttpBasicsTest`, `MigrationTest`, and the web walk below                                                                                |

```bash
cd apps/api && ./mvnw verify      # 140 tests, Spotless check
```

## `docs/demo-p0.md` with the existing web

```bash
cp .env.example .env
pnpm --filter @meal/api-legacy db:local   # terminal 1: PostgreSQL on :5433
pnpm --filter @meal/api db:seed           # or the legacy pnpm db:migrate && pnpm db:seed
pnpm --filter @meal/api start:dev         # terminal 2: Java API on :3001 (API_PORT)
pnpm --filter @meal/web dev               # terminal 3: web on :3000, /api goes to :3001
```

Then follow `docs/demo-p0.md`:

- `PNJP000001` / `123456` → "Đổi mã PIN" → `/today`; signing in again uses the new PIN.
- `PNJP000003` (HC admin) → "Nhân viên": the whole factory, filters, "Cấp lại mã PIN".
- `PNJP000002` (dept admin) → "Nhân viên": only Xưởng Chế tác; `/system` is not offered.
- "Đăng nhập bằng Microsoft" → the mock page → `dung.pham@pnjp.test` → `/today`, no PIN change; "Hệ thống" lists the HC and system admins.

Screens of P1 and P2 (menus, registration, org, settings) still need the legacy API until J2 and J3; with only the Java API running they show their error state.

## Legacy PIN hashes keep working

A database seeded or used by the legacy API signs in on the Java API with the same PINs: `PinHasherTest` verifies a hash made by the Node `argon2` package, and the Java hash format is the one Node reads.

## The seed is the legacy seed

```bash
createdb a && createdb b
DATABASE_URL=postgresql://…/a pnpm --filter @meal/api db:seed
DATABASE_URL=postgresql://…/b?schema=public pnpm --filter @meal/api-legacy db:migrate && … db:seed
psql a -Atc 'select "employeeCode",name,email,"orgUnitId","mealUnitId","startDate" from "Employee" order by 1' | md5sum
psql b -Atc '…same query…' | md5sum      # same hash
```
