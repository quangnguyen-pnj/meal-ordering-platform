# Demo – J2, P1 on Java

> Written while both APIs existed. Since J3 the Java API serves every screen, runs the jobs in Compose, and the current setup is in the [README](../README.md).

The exit criteria of J2 (plan, "J0–J3 – Java migration") and how to see each one. Needs a JDK 25 in `JAVA_HOME`. Setup is the same as [demo-j1.md](demo-j1.md).

```bash
cd apps/api && ./mvnw verify      # 311 tests, Spotless check
```

## The P1 exit criteria hold on the Java API

| P1 exit criterion                                                                            | Automated in `apps/api`                                                                        |
| -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| closeAt tests for publishing on Monday, Thursday 23:59, Friday 00:01, Saturday, Sunday 20:00 | `CloseAtTest` (meal-rules), plus a regular and a late publish end to end in `MenuTest`         |
| The HC admin can build and publish a week                                                    | `MenuTest` "building and publishing a week", "BR-04: late publish"                             |
| Employees see their canteen's published menu and never a draft                               | `MenuTest` "BR-02: employees never see a draft", "BR-15: menus are per canteen"                |
| A mapping import with one bad row is rejected with row-level errors and applies nothing      | `OrgTest` "BR-64 exit criterion: one bad row rejects the file…"                                |
| Changing a mapping leaves existing snapshot rows unchanged                                   | `OrgTest` "BR-64: forecast lines keep the mapping they were written with; they are write-once" |

The change requests of 2026-10-23 and 2026-10-27 (dish types as master data, one library per canteen without duplicates, library import, pick-up floor, publish without a draft step, notices) are covered by the same suites and by `EmployeesAdminTest`.

## `docs/demo-p1.md` with the existing web and only the Java API

```bash
pnpm --filter @meal/api db:seed           # on an empty database
pnpm --filter @meal/api start:dev         # Java API on :3001, jobs on (JOBS_ENABLED)
pnpm --filter @meal/web dev               # web on :3000
```

Then follow sections 2 to 4 of `docs/demo-p1.md`:

- **HC admin `PNJP000003`:**
  - The yellow BR-06 banner shows after Monday 12:00.
  - **Thư viện món**: add dishes and a dessert.
  - **Thực đơn tuần**: build the week and publish it. The deadline is fixed.
  - **Đơn vị nhận cơm**: flags, mapping, dept admins, the two lists, and **Nhập / xuất Excel** (download, change one floor code to `T9`, upload: rejected with the row).
  - **Đồng bộ từ DMP**: a `SyncRun` appears under **Hệ thống**.
  - **Nhà ăn, tầng, trung tâm chi phí** and **Cài đặt**.
- **Employee `PNJP000001`:** BR-14 notices appear in the banner area after the HC admin replaces or removes a dish they registered for.

Still on the legacy API until J3: everything about registering (`/week`, `/today`, `/team`, `/history`, `/admin/registrations`, `/admin/print`). With only the Java API running, those pages show their error state.

## Jobs

With `JOBS_ENABLED=true` the four jobs run on their legacy schedules (Vietnam time):

- sync-org-employees: 02:00
- menu-publish-warning: hourly
- close-week: every minute
- reselect-deadline: every minute

Each job holds a row in the `shedlock` table while it runs, so a second API instance skips it:

```bash
psql "$DATABASE_URL" -c 'select name, locked_at, lock_until from shedlock'
```

`JobsTest` shows what each job does, with fake time. Until J3 the Compose `api-java` service keeps jobs off, because the legacy API runs them.
