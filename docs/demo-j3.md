# Demo – J3, P2 on Java and switch-over

The exit criteria of J3 (plan, "J0–J3 – Java migration") and how to see each one. Needs Node >= 22.12, pnpm 9 and a JDK 25 in `JAVA_HOME`.

```bash
pnpm install
pnpm lint && pnpm typecheck && pnpm test   # Java (365 tests), web (89), shared (25)
```

## The P2 exit criteria hold on the Java API

| P2 exit criterion                                                                                                                                                                                                                 | Automated in `apps/api`                                                                                           |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| With fake time: register on Tuesday, free edits, forecast snapshot at Thursday 23:59, one employee adds a meal and another with no forecast registers for Monday at 07:59, full lock at 08:00 on day D, both shown as differences | `RegistrationTest` orders 20–28 (week N = 2027-10-04)                                                             |
| The bulk grid saves 50 employees × 5 days                                                                                                                                                                                         | `RegistrationTest` "saves 5 days of 50 rows, one batch per day" (and one bad row rejects the batch)               |
| The forecast snapshot is unchanged after edits                                                                                                                                                                                    | `RegistrationTest` "BR-07: the forecast snapshot is unchanged after every edit, extra, removal and cancellation"  |
| BR-20 (no portion limit), BR-22 to BR-25 windows, BR-23 differences, BR-36 leavers                                                                                                                                                | `RegistrationRulesTest` (meal-rules), `RegistrationTest`                                                          |
| BR-14 reselection at the lock and the red "dish changed" note, BR-27 colleagues, the Excel import, the not-registered filter, every 403                                                                                           | `RegistrationTest` orders 29–62, `RegistrationImportTest` (meal-rules)                                            |
| A missed close-week job heals itself on the next request                                                                                                                                                                          | `RegistrationTest` "BR-07: without the job, the first request after closeAt takes the snapshot before any change" |

`docs/demo-p2.md` lists the same walk step by step, with the fake times and the expected answers.

## `docs/demo-p2.md` with the web and the Java API

```bash
cp .env.example .env
pnpm db:local        # terminal 1: PostgreSQL on :5433 (as a normal user, not root)
pnpm db:seed         # terminal 2: Flyway migrations + demo data
pnpm dev             # Java API :3001 (jobs on), web :3000
```

Sign in at http://localhost:3000 (PIN `123456`, changed at first sign-in), then follow `docs/demo-p2.md`:

- **HC admin `PNJP000003`:** publish the coming week on **Thực đơn tuần** (dishes on **Thư viện món** first).
- **Employee `PNJP000001`:**
  - **Đăng ký tuần**: use the + / − steppers, then **Lưu đăng ký**. The toast gives the week's meals and the deadline.
  - **Hôm nay**, **Lịch sử** and **Đơn vị của tôi** read the same registrations.
- **Dept admin `PNJP000002`:**
  - **Đăng ký tuần (quản trị)**: progress, **Chưa đăng ký** with the Khối / Trung tâm / Phòng ban filter, and **Nhập theo bảng** (the grid, Excel template and import).
  - **In phiếu đăng ký** prints a unit's forms.
- **System admin `PNJP000004`:** moves the fake clock (`PUT /api/dev/clock`) for the after-closeAt and day-D steps.

## The legacy API is gone

- `apps/api-legacy`, Prisma and the pg-boss jobs are deleted. `docker/docker-compose.yml` runs the Java API as `api`; nginx still sends `/api` to `api:3001`.
- `packages/shared` keeps what the web runs: form schemas, roles and codes, settings, the permission matrix, time helpers and the menu warnings. `pnpm --filter @meal/shared test` covers them.

```bash
git ls-files apps/api-legacy | wc -l            # 0
grep -rn "@meal/api-legacy\|prisma" apps packages --include=*.ts --include=*.tsx --include=*.json | wc -l   # 0
```

## Web types generated from OpenAPI

```bash
pnpm --filter @meal/api openapi       # writes apps/api/openapi.json from the running description
pnpm --filter @meal/web api:types     # writes apps/web/src/lib/openapi.d.ts with openapi-typescript
git status --short                    # nothing changes on a clean checkout
```

- `OpenApiTest` fails when `apps/api/openapi.json` is stale, or when two classes would share one schema name.
- `apps/web/src/lib/api-types.test.ts` fails when `openapi.d.ts` is stale.
- `apps/web/src/lib/api-types.ts` names the generated types and stops compiling if the web's error codes differ from the API's.
- To see a nullable field reach the web, look at `forecastTotal: number | null` in `MyDayView` of `openapi.d.ts`. It comes from `@Nullable Integer forecastTotal` in `RegistrationViews.java`.
