# Phase 0 – how to demo each exit criterion

> The "Automated" references name test files of the NestJS API, which was removed in J3; their Java ports are listed in [demo-j1.md](demo-j1.md).

"Done when" from the plan, with the exact way to show each one. Commands run from the repository root.

## Setup

**Without Docker** (what this phase was verified with):

```bash
pnpm install
cp .env.example .env
```

In `.env`, point `DATABASE_URL` at the local database (the example value already does: `localhost:5433`, user `meal`).

```bash
pnpm db:local                         # terminal 1: real PostgreSQL in .pg-data, stays running
pnpm db:seed                          # terminal 2: Flyway migrations, then the demo data
pnpm dev                              # API on :3001, web on :3000
```

Open http://localhost:3000.

**With Docker** (written, not yet run on a machine with Docker):

```bash
cp .env.example .env
docker compose -f docker/docker-compose.yml --env-file .env up --build
```

Open http://localhost:8080. The API applies migrations and seeds on start.

## Seeded accounts

Every seeded employee starts with the PIN in `SEED_INITIAL_PIN` (default `123456`) and must change it at first sign-in.

| Code         | Role         | Notes                                                       |
| ------------ | ------------ | ----------------------------------------------------------- |
| `PNJP000001` | Employee     | Production worker, level-6 node under Tổ Nhẫn               |
| `PNJP000002` | Dept admin   | Manages Xưởng Chế tác and everything below                  |
| `PNJP000005` | Dept admin   | Manages Xưởng Kim hoàn (for cross-scope checks)             |
| `PNJP000003` | HC admin     | Whole factory                                               |
| `PNJP000004` | System admin | `FIRST_SYSTEM_ADMIN_CODE`                                   |
| `PNJP000006` | Employee     | Under Kho vận: no meal unit, so unassigned (BR-60)          |
| `PNJP000007` | Employee     | Under Sửa chữa: meal unit with no floor, so invalid (BR-63) |

## 1. All 4 roles can sign in

Manual: sign in as `PNJP000001`, `000002`, `000003`, `000004` at `/login`. Each is sent to `/change-pin` first; after changing the PIN the navigation differs per role (employee: "Của tôi" only; dept admin: operational screens; HC admin: all but "Hệ thống"; system admin: settings, org, audit and "Hệ thống").

Automated: `apps/api/test/auth.e2e-spec.ts` ("all 4 roles can sign in"), `apps/web/src/lib/nav.test.ts` (navigation per role).

Also: PIN lock after 5 wrong attempts, forced PIN change, PIN reset with a temporary PIN, and Microsoft sign-in (mock) are covered in the same e2e file. Mock Microsoft: `/login` → "Đăng nhập bằng Microsoft" → type `an.nguyen@pnjp.test`.

## 2. Tests prove 403 on out-of-scope access

```bash
pnpm --filter @meal/api test
```

Look at `apps/api/test/rbac.e2e-spec.ts`. By hand, as the dept admin of Xưởng Chế tác:

```bash
curl -c jar -H 'content-type: application/json' -d '{"employeeCode":"PNJP000002","pin":"<your PIN>"}' localhost:3001/api/auth/pin/login
# in scope: 200 with a temporary PIN
curl -b jar -X POST localhost:3001/api/employees/PNJP000001/pin/reset -H 'content-type: application/json' -d '{}'
# out of scope (an employee under Xưởng Kim hoàn): 403
curl -b jar -X POST localhost:3001/api/employees/<code under XKH>/pin/reset -H 'content-type: application/json' -d '{}'
```

The same file checks list endpoints are filtered to the caller's scope, role-level 403s (`/api/system/sync-runs`, `/api/dev/clock`), and that a route with no access rule is denied.

## 3. Fake time at Thursday 23:59:30 and Friday 00:00 give different results

```bash
pnpm --filter @meal/shared test      # rules/deadlines.test.ts (BR-03)
pnpm --filter @meal/api test         # src/clock/clock.service.spec.ts, same case through ClockService
```

Live, as the system admin with `CLOCK_FAKE_ENABLED=true`:

```bash
curl -b jar -X PUT localhost:3001/api/dev/clock -H 'content-type: application/json' -d '{"at":"2026-10-22T23:59:30+07:00"}'
curl -b jar -X PUT localhost:3001/api/dev/clock -H 'content-type: application/json' -d '{"at":"2026-10-23T00:00:00+07:00"}'
curl -b jar -X PUT localhost:3001/api/dev/clock -H 'content-type: application/json' -d '{"at":null}'   # back to real time
```

Fake time also ages sessions, so sign in again after a large jump. With `NODE_ENV=production` the API refuses to start with `CLOCK_FAKE_ENABLED=true` and the route does not exist.

## 4. Mapping resolution passes on the 6-level seed tree

```bash
pnpm --filter @meal/shared test      # rules/mapping.test.ts: BR-60, BR-62, BR-63 on synthetic trees
pnpm --filter @meal/api test         # src/seed-data/seed-data.spec.ts: the same rules on the real seed tree
```

The seed spec covers: nearest ancestor wins (Tổ Dây chuyền floor T3 beats its workshop's T2), cost-center override, an invalid meal unit (Sửa chữa, no floor), unassigned employees (Kho vận, no flagged ancestor), and that every one of the 1,700 employees resolves. `test/seed.e2e-spec.ts` repeats it against the database after `db:seed`.

## 5. docker compose up runs the whole system

See "With Docker" above. Expected: four containers (`postgres`, `api`, `web`, `nginx`), the API healthy, `http://localhost:8080/login` loading, and sign-in working. **Not yet run:** no Docker was available while building this phase. The first run on a Docker machine is the real check; report anything that fails.

## Checks run for this phase

```bash
pnpm lint && pnpm typecheck && pnpm test && pnpm build
```
