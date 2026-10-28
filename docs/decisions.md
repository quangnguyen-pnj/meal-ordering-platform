# Decisions log

Choices made while building, where the plan (`docs/plan.md`, MVP Plan v1.1) was silent or a tool forced a trade-off. Newest phase last. Anything that changes a business rule belongs in `open-questions.md` instead.

## Phase 0 – Foundation

### Repository and tooling

- **One repo, `meal-ordering`.** A single pnpm + Turborepo monorepo (`apps/api`, `apps/web`, `packages/shared`).
- **`docs/plan.md` was not committed** in the original `meal-ordering` repo because it lists unpatched DMP security findings (section 8). Since the move to `meal-ordering-platform` the owner chose to commit it; making the repo private is recommended while those findings are open.
- **Keep the checkout path short on Windows.** `node_modules/.pnpm/...` plus a long parent folder exceeds the Win32 working-directory limit and breaks postinstall scripts (esbuild, Prisma, argon2) and Node's package lookup. `D:\Web Programming\Projects\meal` is fine.
- **pnpm 9.15.9** pinned via `packageManager`. Node **>= 22.12** is required because `openid-client` v6 is ESM-only and the CommonJS API loads it with `require()` of an ES module.
- **ESLint enforces plan rule 3** (`no Date.now()` / `new Date()` in business code) with `no-restricted-syntax`. Only the clock service, specs, tests and the seed are exempt.
- **No `nestjs-zod`.** The plan lists it, but a 20-line `ZodValidationPipe` (`apps/api/src/common/zod-validation.pipe.ts`) gives the same shared-schema validation without a dependency pinned to a Zod major.
- **Deferred stack items:** Motion, Zustand and dnd-kit (menu builder, P1), pg-boss and exceljs (first job / first export), Playwright (P7). Nothing in P0 needs them.

### Data model and rules

- **`AuthSession.method` (`PIN` | `ENTRA`) was added** to the table in plan 6.3. A forced PIN change only applies to PIN sessions; someone signing in with Microsoft must not be asked for a PIN they never use.
- **`Employee.mealUnitId` is the nearest flagged node even when that unit is invalid** (no floor or canteen, BR-63). Validity is derived by `resolveEmployeeMealAssignment`, so the "unassigned" list can say _why_ (no meal unit vs. invalid meal unit).
- **Cost-center precedence:** employee override, then inherited org mapping, then the cost center DMP supplied for the employee. This is one reading of "DMP is the default and an admin mapping overrides it" (BR-62) and is listed in `open-questions.md`.
- **`registration.close` stores the last accepted minute** (`{ weekday: 4, time: "23:59" }`). `regularCloseAt` adds one minute and returns the exclusive instant (Friday 00:00, BR-03). Only the regular close lives in P0; late publish (BR-04, BR-05) is P1.
- **Org paths** are materialized as `/<id>/<id>/` with a trailing slash so "is within" is `startsWith` and `/ou_2/` never matches `/ou_20/`.
- **Vietnam time is a fixed UTC+7** in `rules/time.ts` (no DST since 1975), so the shared package needs no timezone library.
- **Seed ids are deterministic** (`ou_<dmpCode>`, `fl_T1`, `cc_CC01`, ...) so tests and docs can name them. The PRNG is seeded, so the 1,700 employees are identical on every run. One argon2 hash of `SEED_INITIAL_PIN` is reused for all of them (hashing 1,700 PINs would take over a minute).
- **Placeholders in the seed:** DMP company code `PNJP`, caterer unit price 35,000 VND. See `open-questions.md`.

### Authentication

- **Sessions:** 32 random bytes in an httpOnly, SameSite=Lax cookie; only the SHA-256 is stored. Lifetime `SESSION_TTL_HOURS` (default 12). Expiry is judged by `ClockService`, so fake time also ages sessions.
- **PIN lock:** `pin.maxAttempts` / `pin.lockMinutes` come from `Setting`. The fifth wrong PIN locks the account and resets the counter. A locked account refuses even the right PIN. An unknown employee code runs a dummy argon2 verify and returns the same 401 body as a wrong PIN.
- **Changing a PIN** also checks the current PIN against the same lock counter, and ends the employee's other sessions. **Resetting a PIN** (admin) ends all of the employee's sessions, clears any lock, and returns the temporary PIN once; it is never written to the audit log.
- **Entra:** `ENTRA_MODE=oidc` uses OIDC Authorization Code + PKCE (`openid-client`). It is **untested against a real tenant** because the app registration does not exist yet. `ENTRA_MODE=mock` serves a local fake sign-in page and is **rejected at startup when `NODE_ENV=production`**. The PKCE verifier and state travel in a short-lived HMAC-signed cookie. Zero or several employees matching the email both end on the "account not linked" screen.
- **First system admin:** `FIRST_SYSTEM_ADMIN_CODE` is granted `SYS_ADMIN` at API start (and by the seed). If the employee does not exist yet it retries later (the P1 employee sync should call `SystemAdminBootstrap.ensure()`).

### Authorization

- **The permission matrix (plan section 3) is data** in `packages/shared/src/rules/permissions.ts`, evaluated as (role, action, scope). The API's global `AccessGuard` and the web navigation both read it; the API is the only enforcer.
- **Fail closed:** a route with none of `@Public`, `@Authenticated` or `@RequirePermission` is denied, and a test checks every real controller route declares one.
- **Scopes are declarative** (`{ type: 'employeeParam' | 'orgUnitParam' | 'list' | 'factory' | 'system' | 'self' }`), resolved by the guard, never inside handlers. `list` routes receive `req.listScope`, which the service turns into a Prisma filter.
- **403 vs 404:** a caller who could see the whole population (HC or system admin) gets 404 for a missing resource; everyone else gets 403, so a dept admin cannot probe which employee codes exist.
- **Extra endpoints beyond the plan's P0 list**, all behind the guard: `GET /api/employees` (scoped finder next to PIN reset, gated by `pin.reset`), `GET /api/system/sync-runs` (HC and system admins, exercises a role-level 403), `GET/PUT /api/dev/clock`, `GET /api/health`.
- **Fake time** (`CLOCK_FAKE_ENABLED`) is refused when `NODE_ENV=production`, both by env validation and by `ClockService`. The `/api/dev/clock` controller is only registered when fake time is allowed, requires a system admin, and is audited.

### DMP

- `DmpClient` has four read methods. `MockDmpClient` serves the same deterministic org tree and employees as the seed, plus attendance and leave from `src/dmp/mock/scenarios.json` (`on-time`, `late`, `forgot-to-check-in`, `morning-leave`, `afternoon-leave`, `api-error`, `too-little-data`), chosen with `DMP_MOCK_SCENARIO`.
- `HttpDmpClient` is a skeleton: service-token login, a request helper with the 10 s timeout and 3 retries with backoff. The endpoint methods reject with `NOT_IMPLEMENTED` until P1 (org, employees) and P3 (attendance, leave).

### Web

- **Next.js 15 (App Router), React 19, Tailwind CSS 3.4.** shadcn-style components are hand-written (`components/ui`). `output: 'standalone'` is switched on only with `NEXT_OUTPUT=standalone` (the Docker build), because tracing creates symlinks that ordinary Windows accounts cannot.
- **Navigation is derived from the permission matrix**, so it can disagree with the plan's 4.1 screen table in two places: the system admin also sees `/admin/org`, `/admin/master`, `/admin/employees`, and `/admin/settings`, because the matrix grants those. See `open-questions.md`.
- **Placeholder pages:** every route in the navigation registry renders "coming in phase Px" through one catch-all page; unknown routes are 404. The banner area exists but is fed an empty list until P1/P3/P4.
- **Auth gate:** `middleware.ts` only checks that the cookie exists; the `(app)` layout validates it against `/api/auth/me` on every request and redirects to `/login` or `/change-pin`.
- The browser only calls same-origin `/api`. In `pnpm dev` a Next rewrite proxies it to `API_INTERNAL_URL`; in Docker nginx does.

### Infrastructure

- **No Docker on the build machine**, so `docker-compose.yml` and the Dockerfiles were written but **not built or run here**. Their pieces were checked separately: the compiled API against a real PostgreSQL, the web production build in a browser, and the compose YAML parses.
- **Tests use a real PostgreSQL without Docker** (`embedded-postgres`, started by `apps/api/test/global-setup.ts`, initialised as UTF8 because the Windows default code page cannot store Vietnamese). `pnpm --filter @meal/api db:local` runs the same thing as a persistent dev database. Set `TEST_DATABASE_URL` to use an existing empty database instead.
- The compose stack runs with `NODE_ENV=development` and seeds on start so there is something to sign in with; production needs `ENTRA_MODE=oidc`, a real `SESSION_SECRET` and no seed.
- The API image keeps dev dependencies because the entrypoint needs the Prisma CLI (`migrate deploy`) and `tsx` (seed). Slimming it is a P7 job.
- **`pnpm dev` runs the API through `tsc-watch`, not `tsx`.** Nest resolves constructor dependencies from `emitDecoratorMetadata`, which esbuild (inside `tsx`) does not emit, so `tsx watch` crashed at boot and the web proxy answered every `/api` call with 500. `tsx` is still fine for the seed and scripts, which use no Nest DI.

## Phase 1 – Org structure and menu

### Data model

- **Migration `20261020000000_p1_org_menu`** adds `Dish`, `Dessert`, `MenuWeek`, `MenuDay`, `MenuItem`, `MenuDayDessert`, `Registration`, `ForecastLine`, `Notification`, `MappingImport`, plus `OrgUnit.dmpCostCenterCode`, `RoleAssignment.revokedAt` and `SyncRun.details`.
- **`Registration` and `ForecastLine` exist from P1, without their flows.** BR-14 (P1) has to move or flag registrations, and the P1 exit criterion "changing a mapping leaves existing snapshot rows unchanged" needs snapshot rows. Registration flows and the close-week job that writes `ForecastLine` stay in P2.
- **`ForecastLine` is write-once in the database**: a trigger rejects `UPDATE` and `DELETE` (rule 7). `FinalEmployeeLine` and `FinalCount` get the same trigger in P4.
- **Nothing on a menu is deleted.** Removed or replaced items and desserts get `removedAt`. A replaced item points to its replacement (`replacedByItemId`). BR-14's "move registrations to the replacement" is done literally: a new item is created and the registrations move to it, so forecast lines keep the item they were taken against.
- **Role assignments are revoked, not deleted** (`revokedAt`). Revocation takes effect on the next request, because the principal is loaded on every request. `FIRST_SYSTEM_ADMIN_CODE` does not re-grant a revoked first admin.

### Rules (packages/shared)

- `computeCloseAt` (BR-03, BR-04): published before the regular closeAt keeps it; at or after it, closeAt = publish + `registration.latePublishHours`. Publishing at exactly Friday 00:00 is already late, because closeAt is exclusive.
- `dayFreeEditDeadline` (BR-05) = the earlier of closeAt and the day's own `day.lockTime`.
- `lastAcceptedMinute` (BR-03 display): screens show closeAt minus one minute, worded "đến hết 23:59" ("until 23:59"). The same applies to a late closeAt (see Q19).
- `menuPublishWarnAt` / `isMenuPublishWarningDue` / `upcomingWeekStart` (BR-06): the warning concerns next Monday's week and starts at `menu.publishWarnAt` of the current week.
- `mealDisplayName` (BR-12) shows `[Loại] Tên món`, e.g. "[Chay] Gạo lứt muối mè", with literal brackets. The plan's example "Mặn Cá kho tộ" has none; the product owner chose brackets on 2026-10-21.
- `checkMenuMix` (BR-13): the usual mix (1 Chay, 1 Cháo, 2–3 Mặn) is a constant in the rule, not a setting; plan 6.4 has no key for it. It only warns.
- `planMenuDayEdit` (BR-11, BR-14): a saved day is a list of `{ id?, dishId }`. An existing id with a new dish is a replacement, a missing id is a removal, and the same dish twice on a day is rejected.
- `buildDmpTree` / `mapDmpLevel` (BR-61): the level is the depth from the root (assumption A14). Any duplicate code, unknown parent, cycle or 7th level rejects the whole tree.
- `validateMappingRows` (BR-64): a row states the complete mapping of its target, and a blank cell means "inherit". Exporting and re-importing the same file therefore changes nothing; a test proves the round trip. Employee rows may only set the cost center (BR-63).

### API

- **New endpoints**, all behind the guard with a 403 test each: `/api/admin/dishes`, `/api/admin/desserts` (`dishes.manage`); `/api/admin/menu/*` (`menu.manage`); `/api/menu/my-week` (`menu.registerOwn`, self); `/api/admin/master/*` and `/api/admin/org/*` (`org.manage`; sync is `sync.manage`); `/api/employees/:code/cost-center-override` (`org.manage`); `/api/admin/settings`, `/api/admin/supplier` (`settings.manage`); `/api/system/roles` (`roles.assignPrivileged`); `/api/me/banners`, `/api/me/notifications/:id/read` (any signed-in user, always filtered to the caller).
- **`GET /api/employees`** now returns the resolved meal unit, floor, canteen and cost center (with names and source), and filters by org node, floor, canteen, cost center, meal status and status. Floor, canteen and cost center are inherited values, so it filters after resolution in memory (about 1,700 employees).
- **Mapping import** is multipart (`file`, max 5 MB). The upload is validated and stored as `MappingImport` (PREVIEW or REJECTED); applying re-validates against current master data, locks the import row (`FOR UPDATE`) and writes every change plus one audit entry per changed target in one transaction. The second sheet of the export lists the valid codes.
- **Org sync** reads DMP first, validates, then writes everything in one transaction under a Postgres advisory lock. It never touches meal-unit flags or mapping, deactivates nodes missing from DMP, creates DMP-sourced cost centers it has not seen, recomputes meal units, then calls `SystemAdminBootstrap.ensure()`. Employees whose node is unknown are skipped and listed in `SyncRun.details`. New employees have no PIN, so they sign in with Microsoft or get a PIN reset.
- **`HttpDmpClient.getOrgTree` / `getEmployees`** are implemented with sequential pages of 500. Field names are a best reading of the survey and live in `dmp-parsers.ts` with a TODO (Q17).
- **Publishing a menu needs a saved week** and is a conditional update, so two clicks cannot both publish. A published week stays editable under BR-14 except for days whose lock has passed (Q15) and turning a serving day off (Q16).
- **BR-14 notices** go only to employees with registrations on the changed item. They are shown as dismissable banners.

### Jobs

- **pg-boss 10** (CommonJS; v11+ is ESM-only), in schema `pgboss`, both jobs scheduled with `tz: Asia/Ho_Chi_Minh`. `sync-org-employees` runs at 02:00. `menu-publish-warning` runs hourly and checks `menu.publishWarnAt` itself, so a changed setting needs no new schedule. The BR-06 banner is computed on every request, so the job only logs.
- **`JOBS_ENABLED`** (default true) turns pg-boss off; tests set it false and call the handlers directly.

### Web

- Screens: `/admin/dishes`, `/admin/menu`, `/admin/master`, `/admin/org`, `/admin/employees`, `/admin/settings`, `/system`, and `/week`, which shows the published menu read-only until registration arrives in P2. Other routes still use the placeholder.
- **Menu builder:** the week draft lives in a Zustand store (`lib/menu-builder-store.ts`, unit-tested); dnd-kit drags library entries onto day columns. Every drag action also has a button (select a day, then "Thêm"), so it works with a keyboard and on a tablet. The mix warning is computed live with the shared rule.
- The layout fetches `/api/me/banners` on every request. Notices can be dismissed.
- **"Đầu mối nhận cơm" is the UI name for the BRQ "đơn vị suất ăn"** (product owner, 2026-10-22): admins found "đơn vị suất ăn" unclear next to every node. Code, API and docs keep "meal unit" / BR-60. The "unassigned" tab is labelled "Chưa đăng ký cơm được", since it also lists employees whose meal unit lacks a floor or canteen.
- **`/admin/org` explains the meal-unit flag** instead of a bare checkbox:
  - A Có/Không choice says what each answer does for the node's employees.
  - A "Hiện trạng" line shows which meal unit the node's employees follow, where they eat, and how many cannot register.
  - Floor and canteen hints name the meal units a value set on this node reaches. A value with no reach is dimmed, because floor and canteen are only read at meal units (BR-63).
  - Cost center sits in its own "Chi phí" section, since it is resolved per employee.
  - The tree marks each node with its group: meal units show "Đầu mối nhận cơm · N người", other nodes show "→ <meal unit>", and nodes with employees but no meal unit show a warning.
  - "Chỉ xem đầu mối nhận cơm" lists every meal unit with its path, headcount, floor, canteen and validity.
  - The tree API now returns each node's effective `mealUnitId` (BR-60). Counts and reach are derived client-side in `lib/org-tree.ts`.
- **Dept admins add up down the tree:** an assignment covers its node and everything below it (path prefix). An admin assigned lower down is added to the ones above, not swapped in. The node panel lists the admins assigned there, and separately "Quản lý từ cấp trên" with the admins of active ancestors. Those have no "Thu hồi" button; they are revoked on the node they were assigned to.

### Tooling

- **Tests on Linux without the embedded PostgreSQL's ICU 60:** set `TEST_DATABASE_URL` to an empty disposable database (the existing escape hatch). Test files clean up what they create, except `ForecastLine` rows, which are write-once by design.

## P1 UI review (between P1 and P2)

A UI/UX pass over every P1 screen, asked for after a review. No business rule changed; the only API change is that a removed-dish notice now carries `lockTime`.

- **Navigation:** screens that are still placeholders stay in the menu, greyed out with a "Sắp có" badge, and are not links (`NavItem.available`). Flip `available` when a phase delivers a screen. `/` still lands on `/today` (P2); its placeholder links to `/week` meanwhile.
- **Loading:** `app/(app)/loading.tsx` shows a skeleton while a screen renders, and the clicked menu item shows a spinner (`useLinkStatus`, Next 15.3+). Lists show skeleton rows instead of "Đang tải…".
- **Phones and desktops are both targets** (office staff and guards use phones; production workers have no phone at work and no company email, no kiosk). On phones the header is one row and the account block moved into the menu; the employee list turns into cards; tables scroll sideways instead of squeezing columns.
- **Dialogs instead of `window.confirm` and top-of-page forms** (`components/ui/dialog.tsx`, `useConfirm`). The one-time temporary PIN opens in a dialog that has to be acknowledged, so it can never appear off-screen.
- **Settings save together:** edits are drafts until "Lưu thay đổi (n)" in a sticky bar. Each changed key is still its own `PUT` (validated and audited per key); a partial failure keeps the failed drafts and shows the error on that row. Times are typed as 24-hour `HH:mm` text, because a native time input follows the browser locale (12-hour on English systems).
- **Org panel keeps auto-saving** (one audited `PATCH` per change, as before) but says what it saved and offers "Hoàn tác", which sends the previous value as another audited change.
- **Granting roles uses an employee search** (`EmployeePicker`, `/employees?q=`), so the admin sees the name before granting; it searches within the admin's own scope.
- **Menu builder:** each day has "Thêm món", which opens a picker; this replaces "select a day, then Thêm in the library". Dragging from the library stays on wide screens. The unusual-mix note (BR-13) only shows once a day has dishes. Publishing is confirmed in a dialog that repeats the deadline.
- **Login:** Microsoft stays on top (staff with a company email); code + PIN below for workers. PIN fields have a show/hide toggle. Employee codes are not upper-cased on the client while Q4 is open.
- **Rule 3 fix:** the "dish removed" notice said "trước 08:00"; it now uses `lockTime` from the notice payload (the `day.lockTime` setting when the menu was edited). Older notices without it say "trước giờ khóa".

### shadcn/ui throughout

- **Every UI primitive is shadcn/ui** (`apps/web/components.json`, style new-york, Tailwind 3 with `tailwindcss-animate`): Button, Input, Label, Card, Badge, Alert, Table, Tabs, Checkbox, Dialog, AlertDialog, Sheet, Select, Popover, Command, DropdownMenu, Tooltip, Separator, Skeleton and Sonner. Add new ones with `pnpm dlx shadcn@latest add <name>` from `apps/web`. Local changes to the generated files: 44 px touch sizes, `warning`/`success` variants on Alert and Badge, `CardTitle` is the page `<h1>`.
- **Selects follow the device** (`ResponsiveSelect`): a native `<select>` styled like the trigger when the primary pointer is a finger (`(pointer: coarse)`, phones and tablets), so the OS picker opens; the Radix Select with a mouse. Radix reserves `""`, so "Tất cả" / "Kế thừa" travel as a sentinel inside the component.
- **Dialogs return focus to their opener.** Radix only does that for a `Dialog.Trigger`; ours open from code (row menus, handlers), so `AppDialog` and `useConfirm` remember the opener when they open. Confirmations use AlertDialog (Cancel has the first focus, a click outside does nothing); the one-time PIN is an AlertDialog too.
- **Success messages are toasts** (Sonner), with "Hoàn tác" where a change can be reversed (org mapping, archiving a dish). Errors stay inline next to what failed.
- **Kept outside shadcn:** the org tree (no tree primitive), the menu builder's drag-and-drop (dnd-kit), the 24-hour time and PIN fields (built on Input).

### Menu builder drag and drop, and the block style

- **Dragging uses a `DragOverlay`** rendered on `document.body`. The library scrolls (`overflow-y: auto`), so a dish moved by a transform inside it was clipped as soon as it left the library. The entry stays in place, dimmed, and the moving copy says what the drop will do ("Thêm vào Thứ Ba" / "Đổi món …").
- **Dropping a dish on a dish replaces it** (BR-14 once published); dropping it anywhere else on the day adds it; a dessert always joins the day. With a pointer the dish under the cursor wins over its day (`pointerWithin`); keyboard drags use the nearest target. `resolveDrop` holds the rule and is unit-tested.
- **Below 1024 px (`lg`)** the library is hidden, so each day shows "+ Thêm món" and each published dish its "Đổi thành" select. From 1024 px up both are hidden and drag and drop is the only way (by choice of the administration).
- **Blocks are set apart by shadows, not borders**, on a faint warm page background (`--canvas`). Tokens in `tailwind.config.ts`: `shadow-block` (cards, panels, rows), `shadow-raised` (menus, popovers, select lists), `shadow-overlay` (dialogs, sheets, the drag copy). Not `shadow-card`: `card` is also a colour, and Tailwind would then generate a white shadow colour for the same class. Inputs keep their borders; tables keep light row dividers. Inner padding went from 12 to 16 px and page padding to 32 px on desktop.

## Phase 2 – Registration

### Rules (packages/shared)

- `validateDaySelection` (BR-20): any dish of that day's published menu, several dishes and types, each a non-negative integer; no row or 0 means no meal. The same item twice in one selection is rejected as ambiguous. The `maxPerDay` option is the hook BR-20 keeps for a future cap; every caller passes nothing (no cap).
- `employeeDayWindow` (BR-22 to BR-24): `OPEN` before closeAt and the day's lock, `AFTER_CLOSE` from closeAt (or once the snapshot exists) to the lock, `LOCKED` from `day.lockTime` on day D. A late closeAt never passes a day's own lock (BR-05).
- `adminDayWindow` (BR-21, BR-25): `ON_BEHALF` until the lock, then `EXTRA` until finalization. Finalization (DayClose) is P4, so for now the extra window also ends when day D ends (Q20).
- `forecastLinesOf` (BR-07): the forecast is active lines with a positive quantity. Lines waiting for reselection (BR-14) are not in it (Q21).
- `compareWithForecast` (BR-23) gives per-meal forecast, actual and difference. Screens use it to mark a day "Khác dự trù".
- `sameAsLastWeek` / `applyToWholeWeek` (BR-26) add up quantities by type and put them on the **first** dish of that type on the target day, as BR-26 says for "Same as last week". "Apply to whole week" uses the same rule for consistency. A type missing on the target day is reported, never invented. Nothing is saved until the user presses Save. (Removed on 2026-10-23, see below.)
- `isLostByLeaver` (BR-36): a leaver loses meals dated on or after `endDate`, unless that day's lock has already passed. Locked days are left to reconciliation (Q23).
- `DateStringSchema` now rejects impossible dates (`2027-02-30`), so a bad path or query date is a 400, not a 500.

### API

- **New endpoints**, all behind the guard. Each admin endpoint has a 403 test: `/api/registrations/me/{week,day,days/:date,history}` (`menu.registerOwn`, self); `/api/team` and `/api/team/units/:orgUnitId` (`team.view`); `/api/admin/registrations/{units,progress,not-registered}` (list scope); `/api/admin/registrations/units/:orgUnitId/{grid,days/:date}` (`registration.onBehalf`, org-unit scope); `/api/admin/registrations/employees/:code/extras` (`registration.addExtra`, employee scope).
- **Saves are all or nothing.** A week save validates every day first. One changed day that is locked rejects the whole save with `409 DAY_LOCKED` (new error code). A locked day sent unchanged is skipped, so `/week` can always send the full week. The grid saves one day for many employees; one bad row rejects the batch with `{ employeeCode, code, menuItemId? }` details.
- **Rows are never deleted** (rule 7). Removing a meal keeps the row with quantity 0. Saving a day with a new selection resolves its "needs reselection" rows by cancelling them.
- **The grid works on any org node in scope, not only meal units.** A dept admin assigned below a meal unit can still open their own team. Rows are employees below the node who can register, for one canteen at a time (the most common one by default). The response counts the employees it leaves out.
- **Extras (BR-25)** reuse the employee's row for that meal (one row per employee and item in the plan's model): quantity goes up, `isExtra` and `extraReason` are set, and the audit log keeps each addition with its reason.
- **Audit (BR-70):** employee changes after closeAt (`registration.changedAfterClose`), every admin on-behalf change per employee-day (`registration.onBehalf`), extras with their reason, the snapshot itself (`forecast.snapshot`), and each automatic cancellation (`registration.cancelledUnselected`, `registration.cancelledLeaver`, no actor). Employee edits before closeAt are not audited (neither admin actions nor changes after closeAt).
- **"Now" for screens comes from the API** (`now` in the day view, `upcomingWeekStart` from `/units`, the history range), so the web never reads the clock and follows fake time. The week view defaults to the first of the next two weeks that is published and still before closeAt (J1).
- `/team` shows only `key` (employee id), name, meals and eating; no code, no reason (BR-27). Search by code works on the server.
- The not-registered list (BR-21) is everyone in scope who can register, whose canteen's menu is published, and who has no meal in the week (or on the chosen day) (Q22).

### Forecast snapshot (BR-07)

- **`close-week` runs every minute** and snapshots every published week whose closeAt has passed (closeAt differs per week under BR-04). `reselect-deadline` also runs every minute (BR-14 at the lock). Both are idempotent.
- **Self-healing:** every registration request first takes any snapshot that is due, so a missed job never lets a later change into the forecast. Writes take `FOR SHARE` on the week row and the snapshot's conditional update takes the row lock. A write therefore lands either before the snapshot (it is in the forecast) or after it (it is a difference). After the lock, the write re-reads `closedAt` and audits accordingly.
- Snapshot lines carry the meal unit, floor and cost center resolved at that moment, plus the week's canteen (BR-64). The week becomes `CLOSED`, which every menu screen already treats as published.

### Jobs and sync

- **BR-36 in the org sync:** after employees are written, registrations on or after a leaver's `endDate` are cancelled in the same transaction. The count is in `SyncRun.details.mealsCancelledForLeavers`. Employees whose `endDate` is set in DMP cannot register for those days either.
- The BR-14 TODO in `menu.service.ts` is resolved by `ForecastService.cancelUnselectedAtLock`.

### Web

- Screens: `/week` (steppers, closeAt banner, BR-26 shortcuts, per-day lock and difference), `/today` (forecast vs current, countdown from server time, "Không ăn hôm nay", read-only after the lock), `/team`, `/history`, `/admin/registrations` (progress, not-registered, bulk grid with day tabs, Enter moves down, invalid cells on the row, extras with a reason after the lock), `/admin/print` (one table per day, same row order as the grid, browser print CSS).
- **Built on the shadcn/ui conventions of the P1 UI review** (merged from main before P2 closed): `ResponsiveSelect` for every select, Radix `Tabs` (also the grid's day tabs), shadcn `Table`, `WeekNav` with "Về tuần sắp tới", `LoadingRows`, `useConfirm` instead of `window.confirm` (leaving unsaved changes, "Không ăn hôm nay"), `AppDialog` for extras, toasts for success with errors kept inline, `shadow-block` day blocks, and the full-width sticky save bar of /admin/settings. The P2 screens are now `available` in the navigation.
- **Tablets keep the sidebar from `md`**, as main decided ("tables scroll sideways instead of squeezing columns"). At 768 px the bulk grid scrolls sideways with its name column pinned, so the row being typed is always identified. An earlier P2 draft moved the sidebar to `lg`; that was dropped in the merge.
- **P2 copy uses "Đầu mối nhận cơm"** for the meal unit, as decided on main (2026-10-22): the progress table column, `/team`'s intro, and the grid's skipped-employees and row-error texts.
- **A removed-dish notice (BR-14) links to `/week`** ("Chọn lại món"), because it now asks something of the employee before the lock.
- `registration-draft.ts` and `grid-draft.ts` hold the screen logic as pure functions with unit tests. The P1 read-only `week-menu-screen.tsx` is replaced by the registration screen.

### Tooling

- **`apps/web/tsconfig.json` maps `vitest` to the app's own copy.** pnpm installs two copies of vite/vitest (one per `jiti` peer). `@testing-library/jest-dom`'s vitest types resolved the hoisted copy, so `vitest.config.ts` saw a `defineConfig` without `test` and `pnpm typecheck` failed on a fresh install.
- **Linux without the embedded PostgreSQL's ICU 60:** run the API tests against a local server with `TEST_DATABASE_URL=postgresql://…/meal_test pnpm --filter @meal/api test`. Turbo does not pass that variable through, so run the API package directly. The e2e suite leaves write-once `ForecastLine` rows behind, so use a fresh database for each run.

## Change requests of 2026-10-23 (between P2 and P3)

A list of fixes and features from the product owner, with the answers to the questions asked back the same day. Like the P1 UI review, this is not a plan phase; it changes the rules below on the product owner's word, and P3 has not started. Migration `20261023000000_change_requests` converts existing rows; nothing is dropped.

### Fixes

- **Sunday was missing from the weekly menu.** `menuWeekDates` listed Sunday only when the `servingDays` setting included it, so it could never be turned on per week like Saturday. Every week now lists Monday to Sunday; `servingDays` still decides which days serve by default. The not-registered day picker also offers Sunday.
- **A dish could be added twice to the library.** Nothing stopped two dishes with the same name (a double click, or the same name typed again). Now:
  - Library names are stored trimmed with single spaces (`normalizeLibraryName`) and compared without case (`libraryNameKey`).
  - Adding, renaming or restoring an entry whose name matches another entry of the same canteen (and, for dishes, the same type) is refused with `409 CONFLICT`. An archived match counts too (`DUPLICATE_ARCHIVED`): the screen tells the admin to restore it instead.
  - Partial unique indexes on `(canteenId, typeId, lower(name))` for dishes and `(canteenId, lower(name))` for desserts, over non-archived rows, stop two concurrent requests. The migration archives later copies of names that were already duplicated (the oldest stays; menus keep their items).
  - The forms ignore a second submit while one is pending.
  - The same name with another type is another dish ("[Chay] Đậu hũ sốt cà" and "[Mặn] Đậu hũ sốt cà").

### Dish library per canteen; dish types as master data

- **`Dish` and `Dessert` belong to one canteen** (product owner's choice over sharing one dish between canteens). The migration puts each entry in the canteen of the first menu that used it, else the factory's first canteen. Lists, the menu builder and the import all take `canteenId`. A menu can only take new or replacement entries from its own canteen's library; entries already on a menu stay.
- **Dish types are rows** (`DishType`: code, name, colour `#rrggbb`, sort order, optional `minPerDay` / `maxPerDay`, active). The enum `CHAY` / `CHAO` / `MAN` became three rows with the plan's usual mix (Chay 1, Cháo 1, Mặn 2–3); the seed adds "Eat clean" with no bounds. Types are managed on the new "Loại món" tab of `/admin/dishes` (`dishes.manage`, HC admin) and are deactivated, never deleted. An inactive type takes no new dishes and is not checked by the mix note.
- **BR-12 display:** the text form stays `[Loại] Tên món` (`mealDisplayName` now takes the type name). Screens draw `[Loại]` in the type's colour at weight 600 (`components/dish-name.tsx`): library, menu builder, picker, `/week`, `/today`, the bulk grid.
- **BR-13:** `checkMenuMix` reads each type's bounds instead of a constant; still a warning only.

### Publishing a week

- **No draft step is needed.** `POST /api/admin/menu/weeks/publish` takes the same body as a save, saves it and publishes. "Lưu nháp" stays as an option. The old `POST /weeks/:id/publish` remains.
- **New rule:** every serving day needs at least one main dish and at least one dessert (`menuDayGaps` / `menuWeekGaps`). Publishing a week that lacks one fails with `409 MENU_INCOMPLETE` and the list of days; the builder disables "Công bố" and lists the days. Editing a published day must keep one of each. The usual mix by type is still only a warning.

### Replaced dish after closeAt (BR-14)

- When the HC admin replaces a dish, the moved registrations keep the old item in `Registration.replacedFromItemId`.
- An employee whose meal moved, when the replacement happened at or after the week's closeAt, sees a red line on the new dish ("Đã đổi món: bạn đăng ký …") on `/week` and `/today` (`replacedAfterClose`). Only that employee (product owner's choice): someone who picks the new dish later sees nothing. The existing in-app notice is unchanged.

### Pick-up floor per employee; cost center follows the org tree

- **`Employee.floorOverrideId`:** an HC admin (`org.manage`) sets the floor where one employee picks up their meal, e.g. an IT employee of floor 3 who sits on floor 1. A reason is required and the change is audited (`employee.floorOverride.set`). It stays until removed (no date range). The canteen still comes from the meal unit. An employee floor also stands in for a meal unit that lacks a floor, so that employee can register. Forecast lines keep the floor in effect when they were taken (BR-64).
- **Cost center follows the org tree.** Each centre or department has its own cost center and every cost of its employees (meals included) adds up there, so `resolveEmployeeCostCenter` is the inherited node mapping, then the DMP value as a default. **The per-employee cost-center override is no longer read**: the endpoint and screen are replaced by the pick-up floor, the column stays for the record (rule 7), and cost centers are no longer "in use" because of it. The mapping import's `EMPLOYEE` rows now set the pick-up floor only.
- Monthly and yearly cost per cost center is a report (P6); nothing is built for it yet.

### Week number

- `isoWeekOf` (ISO 8601: weeks start on Monday, week 1 holds the year's first Thursday). `WeekNav` shows "Tuần 44" above the range on every week screen.

### Filters by Khối / Trung tâm / Phòng ban

- `GET /api/employees/org-units` (`pin.reset` list scope) and `GET /api/admin/registrations/org-units` (`registration.notRegisteredList` list scope) return the active org nodes in the caller's scope with their DMP level name.
- `OrgLevelFilter` shows cascading selects (first level below the company, then its children; up to three levels), each labelled with the level name DMP uses ("Khối", "Phòng/Xưởng", "Trung tâm", ...). The deepest choice filters by subtree. On `/admin/employees` and the not-registered list (new `orgUnitId` query, applied on top of the guard's scope, so it never widens it).

### Excel imports

- **Dish library:** template download, then upload with `apply=false` (preview) or `apply=true`. A row is "Món chính" with a dish-type code, or "Tráng miệng". One bad row rejects the file; rows already in the library are skipped, so the same file twice adds nothing. Applied in one transaction with one audit entry (`dishLibrary.imported`).
  - The template's "Nhóm" and "Mã loại món" cells are Excel drop-downs on rows 2–2001 (the import limit). "Nhóm" lists the two groups; "Mã loại món" points at the codes on the "Loại món" sheet and accepts blank (desserts have no type). This only helps the user; the server still validates every row, because the drop-down can be bypassed by pasting or by other spreadsheet apps.
- **A unit's week of registrations** (the product owner's meaning of "danh sách đăng ký suất ăn"): the template is the bulk grid as a file (row 1 for people, hidden row 2 with `<date>|<menuItemId>` keys, current quantities filled in), only days still open for on-behalf entry. Upload validates the whole file (`validateRegistrationImport`); applying saves every changed day through the grid's own save, now able to take several days in one transaction (`saveGridDays`), so BR-20, BR-21 and the per-day audit apply unchanged. Same scope as the grid (`registration.onBehalf` on the unit).

### Removed

- "Giống tuần trước" and "Áp dụng cho cả tuần" (BR-26) are gone from `/week`, with their rules and tests.

## Security review of 2026-10-24

Reported: the PIN is visible in full in the browser's DevTools. The checklist that came out of the review is `docs/security.md` (AGENTS.md rule 13).

- **Fixed: a submit before hydration put the PIN in the URL.** The sign-in and change-PIN forms had no `method`, so on a slow network the browser sent a native GET to `/login?employeeCode=…&pin=…`, which then sits in browser history, proxy logs and the Referer header. Both forms now use `method="post"`.
- **Fixed: production could run over plain HTTP.** The api now refuses to start in production without `COOKIE_SECURE=true`, and `docker/nginx.conf` says where TLS must terminate.
- **Added: no caching of API answers.** `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` and `Referrer-Policy: same-origin` on every `/api` response, and no `X-Powered-By`. The web app sends `X-Frame-Options: DENY` and `frame-ancestors 'none'`.
- **Checked, no leak found:** every read endpoint, called as each of the four roles, returns no PIN hash, token hash or secret; a rejected sign-in does not echo the PIN; the session token is only in the httpOnly cookie. `apps/api/test/security.e2e-spec.ts` keeps this checked.
- **Not changed: the PIN in the sign-in request body.** DevTools shows the request before TLS encrypts it, so only someone already at that browser sees it. Hashing the PIN in the browser would not help: the hash becomes the password, and a 6-digit PIN can be brute-forced from any captured hash. HTTPS protects it in transit, and the server stores only an argon2id hash.

## Pagination of lists (UI review, 2026-10-26)

- **One `Pagination` component (`components/ui/pagination.tsx`), 25 rows per page everywhere.** It shows "1–25 / 746", Trước / Sau, and page numbers with gaps from `sm` up ("Trang 2/30" on phones). It hides for a single page, and scrolls the list back into view when its top has gone above the screen.
- **Client-side paging (`usePagination`) for lists the API returns whole:** Đơn vị của tôi, Tiến độ and Chưa đăng ký, Thư viện món (main dishes and desserts), Danh mục, the org meal-unit list, the unassigned and missing-cost-center lists, the mapping import preview and errors, and sync runs. A factory has about 1,700 employees, so these answers stay small; the cost was rendering, not transfer (Chưa đăng ký was 746 rows, 37,000 px tall on a desktop and 67,000 px on a phone). Search or filter changes go back to page 1.
- **Nhân viên keeps server-side paging** (`limit`/`offset`), now 25 instead of 50 per page and with the same component.
- **Not paged, on purpose:** the bulk entry grid (Enter/Tab move through every row and a day saves as one batch), the print sheet, the org tree, the menu builder's library (scrolls inside its panel, with search) and the Excel import dialog (its error list scrolls).
- **Added with it:** a name/code search on Chưa đăng ký and a name search on the dish library (both filter the loaded list), and the team search now waits 300 ms before calling the API instead of calling it on every key. On phones Chưa đăng ký folds the unit columns under the name, so the table no longer scrolls sideways.

## Type, colour and phone layouts (UI review, 2026-10-26)

- **Font: Inter** through `next/font/google` (product owner, 2026-10-26, replacing Be Vietnam Pro). next/font downloads it at build time and serves it from the app, so browsers on the intranet never call Google, but the machine that runs `next build` needs internet access. The Latin and Vietnamese subsets are preloaded. System fonts stay as the fallback.
- **Type scale:** Tailwind's sizes, `text-xs` 12px and `text-sm` 14px. A step up (13/15px) was tried and judged too large with Inter (product owner, 2026-10-26). Page titles are 20px.
  - Inputs are 16px on phones, so iOS does not zoom in on focus, and 14px from `md`.
  - The Radix select (mouse only) is always 14px.
- **Warm palette:** text is a warm near-black (`24 14% 13%`) instead of navy, to sit with the orange primary and the warm canvas. Secondary text is a warm grey (`25 8% 40%`), about 5.9:1 on white. The old blue-grey (`210 13% 62%`) was about 2.6:1, below WCAG AA.
- **Green for "yes" states:** the `success` badge (eating, published, valid) uses a new `--success` green. It used the orange primary before, which read like a warning next to the red badges.
- **Tabs stay on one row and scroll sideways** on narrow screens, instead of wrapping into an uneven second row. A focused tab scrolls itself fully into view.
- **Nhân viên on phones:**
  - Only the search box shows. The other filters open in a bottom sheet from a "Bộ lọc" button, which shows how many filters are on; the sheet has "Xóa bộ lọc" and "Xem n người".
  - Each card's ⋯ menu sits next to the name, and the label column has a fixed width.
  - On desktop the actions column has no visible header, so the table fits a 1366px screen.
- **Đăng ký tuần** shows 3 day columns only from 1536px (`2xl`). Between 768 and 1535px it shows 2, so dish names do not wrap beside the steppers.

## Spacing for Inter (2026-10-26)

Inter has a tall x-height, so the old gaps looked cramped. Spacing goes up one step, mostly in shared pieces:

- **Line height:** `text-sm` 14/22px and `text-xs` 12/18px (about 1.55).
- **Cards:** the header gap goes from 6 to 8px, and padding is 20px on phones (24px from `sm`). Nested blocks (day cards inside a card) lose less width.
- **Sections inside a page card:** 24px apart, was 16px. Forms, dialogs and tab panels use 20px, and filter rows use 16px both ways. Tab panels sit 24px below their tabs.
- **Tables:** header rows 48px, cells 14px top and bottom. **Alerts:** 14px top and bottom.
- **Sidebar:** 24px between groups, 4px between items, and 8px under a group title.
- **Đăng ký tuần / Thực đơn tuần:** day blocks have 16px between rows (20px padding on desktop). Dish rows are 12px apart.
- **Nhân viên cards on phones:** small labels sit above their values in two columns. A long label ("Đầu mối nhận cơm") no longer squeezes the value.

## Đăng ký tuần: Dự trù tuần / Thực tế (product owner, 2026-10-27)

A display change only: one registration per employee-day as before, and the forecast is still the snapshot taken at closeAt (BR-07). No API, schema or rule change.

- **Before closeAt** `/week` shows only the "Dự trù tuần" tab, with the day cards and steppers. "Thực tế" is hidden: what the employee enters now becomes the forecast.
- **After closeAt** (`forecastTaken`) there are two tabs and "Thực tế" opens first (also after moving to another week).
  - **Dự trù tuần** is read-only: one table of the snapshot, a row per dish with a forecast (a dish served on several days shares a row by display name), a column per serving day and totals. Forecast on a dish replaced or removed after the snapshot (BR-14) is not among the day's items; it goes on a "Món đã đổi hoặc bỏ khỏi thực đơn" row, so the day totals still match the snapshot (`forecastGrid`).
  - **Thực tế** holds the day cards, editable until each day's lock (BR-23, BR-24). Each dish shows "Dự trù: n" and, when it differs, the difference "(+1)". This brings back the per-dish forecast line hidden on 2026-10-26, on this tab only; `/today` is unchanged.
- The draft lives above the tabs, so switching tabs keeps unsaved changes. The save bar shows only on the tab with the steppers.

## Thông báo thay đổi thực đơn hiện ngay, gộp gọn (2026-10-27)

Bug report: after an admin replaced a dish in Thực đơn tuần, the employee saw no notice; it appeared only later, together with the next one (removed dish). The API wrote each notice at once (BR-14, `menu.e2e-spec.ts`). The web read them only in the `(app)` layout on the server, and Next.js does not render a shared layout again on client navigation, so the list stayed as it was at the last full page load.

- **Banners are now a React Query list** (`GET /me/banners`), seeded from the server render. They are refetched on each page change, when the window regains focus, and every 60 s while the page is open. Dismissing a notice updates the list in place instead of `router.refresh()`.
- **After the HC admin saves or publishes a menu** the banner list is refetched, so the "menu not published" warning (BR-06) goes away at once. The HC admin's own notices also show at once.
- **Several notices fold into one box:** "Bạn có n thông báo mới về thay đổi thực đơn." with "Xem chi tiết" (a compact list, each item with "Đã xem"), "Đã xem tất cả", and "Chọn lại món" while one of them is a removed dish. A single notice is shown in full, as before. The box is amber when a dish has to be reselected (BR-14), grey otherwise. A notice that needs action comes first. System warnings (BR-06) stay as separate alerts.
- **Per user:** the banners key holds the user id, and a PIN sign-in drops the client query cache (it was kept before, so on a shared machine the next person could briefly see the previous person's cached week), so the next person to sign in in the same tab never sees the previous person's data.

## Bulk entry grid for large units (UI review, 2026-10-27)

A unit can have a few hundred people (Tổ Nhẫn: 296), so "Nhập theo bảng" was several thousand pixels tall: the dish header scrolled away and the totals sat at the very bottom.

- **Still not paged** (see "Pagination of lists"): "Lưu ngày" saves the whole unit, so a page would hide changes and invalid cells that the save includes or is blocked by, Enter would stop at the end of a page, and Ctrl+F would only search one page.
- **The grid scrolls inside a box about one screen high** (`100dvh − 11rem`, at least 22rem). Inside it the dish header (`top`), the name column (`left`) and the totals row (`bottom`) are sticky. The box is one scroll container for both axes, which is what lets the header stick: the old `overflow-x-auto` wrapper made it the sticky container without scrolling vertically. `isolate` keeps its z-indexes under the app's own sticky header. Scroll-padding and `scrollIntoView({ block: 'nearest' })` on Enter keep the focused cell clear of the sticky parts.
- **Toolbar instead of extra rows:**
  - Search by name or code, ignoring case and Vietnamese accents ("nguyen" finds "Nguyễn").
  - One "Lọc dòng" select with counts: Tất cả, Chưa có suất, Đã sửa chưa lưu, Có lỗi, Cần chọn lại món. Empty filters are disabled. A select is used instead of chips so the toolbar stays on one row, also on phones.
  - "Đang hiện n/N · Xóa lọc" only while filtering.
  - The day's window ("Nhập thay được đến …" or the locked warning) sits at the end of the same row. The entry hint moved behind an ⓘ popover, replacing a full-width alert.
  - Day tabs show "T2 02/11" on phones and a lock icon on days that can no longer be entered on behalf.
- **A status filter keeps the rows it matched when it was picked** (`gridFilterGroups`, snapshotted in `GridTab`). Typing a meal into a "Chưa có suất" row, or fixing a "Có lỗi" row, does not make it vanish under the cursor. The filter is worked out again when it is picked again, on another day, after a save or a discard, and falls back to "Tất cả" when nothing matches. The save bar links to "Xem dòng lỗi" / "Xem dòng đã sửa".
- **Totals are always the whole unit**, whatever is filtered; the totals cell says how many people that is. Row numbers stay those of the whole unit while filtering.
- **Shorter rows (about 48px on desktop):**
  - The row number moved into the name column, hidden on phones so more dish columns fit.
  - Badges sit on the code line.
  - An invalid cell is shown by its red border and a tooltip, no longer by an extra line of text.
  - "Thêm suất bổ sung" is a "+" icon button.
  - Cells are 36px high from `md` (44px on touch screens).
- **No table library.** TanStack Table would bring sorting, filtering and paging models, but the hard parts here are sticky layout, keyboard entry and dirty tracking, which it does not provide. A grid component (AG Grid, Glide) would not match the shadcn styling or the Vietnamese copy file. The unit sizes in the seed (up to about 300 people × 5–6 dishes) render without virtualisation. If typing ever feels slow, memoise the rows first; virtualisation (`@tanstack/react-virtual`) would break Ctrl+F and the Enter navigation, which looks cells up in the DOM.
- **Day tabs use manual activation** (`GridDayTabs`, fixed 2026-10-27). With Radix's default, a tab switches as soon as it gets focus. Closing the unsaved-changes confirm gives focus back to the clicked tab, so "Hủy" switched the day again and the confirm reopened every time. Now a click, Enter or Space switches the day; arrow keys only move the focus. Guarded by `registrations-admin-screen.test.tsx`.

## UI copy: "Đơn vị nhận cơm" (2026-10-27)

- **The `/admin/org` screen and its menu item are called "Đơn vị nhận cơm"** (was "Cơ cấu tổ chức"), at the PO's request. The name says what the screen is for: choosing which unit is the meal point and where it eats, rather than naming the data source. "Vị trí nhận cơm" was turned down because it overlaps with the "Nhà ăn, tầng, trung tâm chi phí" catalogue and leaves out cost centers and department admins. The intro leads with the task and mentions DMP last. The tab "Cây tổ chức" and the phrase "theo cơ cấu tổ chức" (cost center source) stay, because there they mean the org tree itself. The URL is unchanged.

## Java migration – J0, foundation (2026-10-28)

Company standard: the backend moves to Java (plan v1.2). The repository moved to `meal-ordering-platform` with its full history; `docs/plan.md` is now committed at the owner's request.

### Stack

- **Java 25 (LTS) + Spring Boot 4.1 (Spring MVC on virtual threads), Maven with the wrapper.** The runtime travels inside the Docker image (`eclipse-temurin:25-jre`), so servers only need Docker, not a matching Java install. No Java EE application server: Spring Boot already uses the Jakarta EE pieces that matter (Servlet, Bean Validation, JPA) and runs as one self-contained process, which is what DMP, also Spring, does.
- **No WebFlux, no Lombok, no MapStruct, no Kotlin.** 1,700 users do not need reactive code; records replace Lombok; mapping is written by hand.
- **Two Maven modules.** `meal-rules` holds the pure business rules and depends on nothing but the JDK, so it stays a plain library like `packages/shared/rules` was; ArchUnit (`ArchitectureTest`) fails the build if it reaches for Spring, JDBC or the API. `meal-api` is the Spring Boot application, package `vn.pnj.meal.api`.
- **Java code style is Spotless with google-java-format** (2-space indent, matching `.editorconfig`), checked in `verify` and in `pnpm lint`.

### Layout during the migration

- **The NestJS API moved to `apps/api-legacy`** (package `@meal/api-legacy`) and the Java API took `apps/api` (package `@meal/api`), so the final layout needs no second rename. The legacy API still serves the web, Compose (`api` service) and the root `db:*` scripts until J3; its 190 tests still pass after the move.
- **Turbo runs Maven** through `apps/api/package.json` and `scripts/mvn.mjs`, which calls `mvnw` or `mvnw.cmd`: `lint` = Spotless check, `typecheck` = compile with tests, `test` = `verify`, `build` = `package`. `MAVEN_CMD` can point at an installed Maven instead of the wrapper. Turbo passes `JAVA_HOME`, `MAVEN_CMD`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS` and `TEST_DATABASE_URL` through (`globalPassThroughEnv`); `TEST_DATABASE_URL` used to be dropped by turbo for the legacy tests too.
- **The Java dev server is `pnpm --filter @meal/api start:dev`, not `dev`,** so `pnpm dev` does not start two APIs on port 3001. Run it with `API_PORT=3002` next to the legacy API.

### Database

- **Flyway V1–V3 are byte-for-byte copies of the three Prisma migrations,** so the schema is identical by construction (`MigrationTest` checks the tables, the history and the `ForecastLine` write-once trigger). Prisma ids stay `text` (cuid); new rows may use other text ids later.
- **`FLYWAY_BASELINE_ON_MIGRATE`** (default false; true in `.env.example` for development) lets the Java API open a database the legacy API already migrated: Flyway records V3 as the baseline instead of re-running V1–V3. It has no effect on an empty database. From now on every schema change is a Flyway migration (AGENTS.md rule 13); the legacy Prisma schema is frozen.
- **One `DATABASE_URL` for everything.** `DatabaseUrl` turns the Prisma-style `postgresql://user:pass@host/db?schema=public` into JDBC settings (`schema` becomes `currentSchema`), applied by `DatabaseUrlEnvironmentPostProcessor` unless `spring.datasource.url` is set directly.

### Configuration

- **`AppProperties` keeps the `.env.example` names.** `application.yml` maps each variable onto the record, and the record's constructor applies every rule of the legacy `env.ts` (Entra and DMP settings required by their modes; in production no fake time, no mock Microsoft page, no example secret, `COOKIE_SECURE=true`), listing all problems at once. Locally the API also reads the repository `.env` (`MEAL_ENV_FILE`, set by the pnpm scripts); real environment variables win over the file.
- **The environment is `APP_ENV`, falling back to `NODE_ENV`,** so one `.env` serves the web and the API. An empty or unknown value stops the API: it must never count as "not production" and allow fake time.
- **Secrets stay out of `toString()`** of `AppProperties` and `DatabaseUrl` (docs/security.md).

### HTTP contract

- **`/api` prefix** through `WebConfig.addPathPrefix` for every `@RestController`, like the legacy global prefix; controllers map their own paths.
- **Every error is the legacy `ApiErrorBody`** (`statusCode`, `code`, `message`, optional `details` and `lockedUntil`). `ApiErrorCode` mirrors `API_ERROR_CODES` until the web reads the codes from OpenAPI (J3). Bean Validation errors become `VALIDATION_FAILED` with `details: [{ path, message }]`, without the value sent. An unknown method on a known path answers 404 `NOT_FOUND`, as NestJS did. Anything unexpected is logged and answered with a bare 500 `INTERNAL`.
- **Security headers** (`Cache-Control: no-store`, `nosniff`, `Referrer-Policy: same-origin`) on every answer, from a servlet filter; Tomcat sends no `X-Powered-By`.
- **`/api/health` is open** until the access guard arrives in J1 (`TODO(J1)` in `HealthController`); every other legacy route is J1–J3 work.

### Clock

- **`ClockService` wraps `java.time.Clock`** and is the only class allowed to read it; `ArchitectureTest` fails on `Instant.now()`, `LocalDate.now()`, `LocalDateTime.now()`, `new Date()`, `System.currentTimeMillis()` and friends anywhere else in main code. Fake time follows the legacy rules (CLOCK_FAKE_ENABLED, never in production). The BR-03 clock test moves over with `regularCloseAt` in J1; the dev clock endpoint needs the guard and the audit log, so it is J1 too.
- **`VnTime` uses the `Asia/Ho_Chi_Minh` zone from java.time** instead of the fixed +7 offset arithmetic of `time.ts`; the results are the same (no DST since 1975) and java.time handles ISO weeks. Its tests are the legacy ones plus a few edge cases.

### Tests

- **Embedded PostgreSQL 17 (`io.zonky.test:embedded-postgres`), no Docker needed.** It runs on Windows dev machines without Docker and on CI; the binaries come from Maven Central, and unlike the Node package it does not need ICU 60 on Linux. The test database is created as UTF8. `TEST_DATABASE_URL` (same form as `DATABASE_URL`) uses an existing empty database instead. If a CI with Docker appears, Testcontainers is a drop-in alternative.
- **Integration tests extend `IntegrationTest`** (whole app, MockMvc, real database, fake time allowed, jobs off).

### Docker

- **`docker/api.Dockerfile` is the Java image** (Temurin 25 JDK to build, JRE to run, Spring Boot layers, non-root user, curl only for the health check). Flyway migrates at startup, so it needs no entrypoint script. The legacy image is `docker/api-legacy.Dockerfile`.
- **Compose keeps the legacy `api`** for the web and adds `api-java` behind the `java` profile, on the same database, started after the legacy API has migrated it. **Not built here** (no Docker daemon in this environment); the same layered jar was extracted and started with `JarLauncher` against PostgreSQL 17 instead.

## Java migration – J1, P0 on Java (2026-10-28)

The P0 part of the API now runs on Java with the same HTTP contract: the existing web signs in (PIN and the mock Microsoft page), forces the PIN change, lists employees by scope, resets PINs and manages HC and system admins against the Java API with no change on its side. P1 and P2 screens still need the legacy API until J2 and J3.

### Scope of J1

- **Endpoints, in their current legacy shape** (not the historical P0 shape, so the web keeps working): `/api/auth/*` (PIN login, change, logout, me, Entra login, callback, mock page), `/api/employees` (finder, `org-units`, PIN reset, pick-up floor), `/api/system/sync-runs`, `/api/system/roles` (list, assign, revoke), `/api/dev/clock`, `/api/health`. The pick-up floor and the role screens came with P1 and the change requests, but they belong to the employee and system controllers ported here.
- **Rules in meal-rules:** time (J0), PIN lock, Entra email, the permission matrix with list scopes, mapping resolution (BR-60 to BR-63) and the BR-03 regular closeAt. The rest of the deadline rules (BR-04 to BR-06) come with menu publishing in J2.
- **Settings:** the 17 keys of plan 6.4 with their defaults and value checks (`SettingKey`), read through `SettingsService` with the legacy fallback (missing or malformed row → default). Editing them (`/api/admin/settings`) is J2.
- **Left for J2:** `/api/me/banners` (the web hides the banner area when it fails), the org sync and `HttpDmpClient.getOrgTree/getEmployees`, settings, master data, org, dishes, menus and the jobs.

### Choices that differ from plan 6.1 (plan updated)

- **Authorization is a Spring MVC `HandlerInterceptor` (`AccessInterceptor`), not Spring Security.** It needs the handler method's annotations and the path variables, which an interceptor sees directly; it is the same shape as the legacy Nest guard, with the same order (public → session → forced PIN change → fail closed → scope → matrix) and the same 401/403/404 answers. Spring Security's filter chain would have added CSRF, session and redirect defaults to switch off. Only `spring-security-crypto` is used, for argon2.
- **Entra sign-in uses the Nimbus OAuth 2.0 SDK directly**, the library under Spring Security's OAuth2 client. Spring's `oauth2Login` keeps its own routes and HTTP-session state; Nimbus lets the API keep `/api/auth/entra/login` and `/callback`, the HMAC-signed transaction cookie and the mock mode. The id_token is checked against the tenant's keys, issuer, audience and nonce. Still untested against a real tenant (no app registration yet), but `OidcEntraProviderTest` runs the whole Authorization Code + PKCE exchange against a local fake provider: wrong state, wrong nonce, a foreign signing key and a refused code are all rejected.
- **No Resilience4j:** the DMP client's retry is a 3-attempt loop with backoff and one re-login on 401, as in the legacy client.

### Data access

- **JPA entities without relationships.** Foreign keys stay plain id fields, so nothing loads lazily behind a service; reads that join or aggregate use `JdbcClient` with SQL. Hibernate keeps the quoted camelCase names (`PhysicalNamingStrategyStandardImpl` + `globally_quoted_identifiers`), Postgres enum columns bind through `PostgreSQLEnumJdbcType`, and JSONB columns are written with `JdbcClient` and `CAST(... AS jsonb)`. `ddl-auto` is `none`: Flyway owns the schema.
- **A JPQL enum literal does not work** (Hibernate casts it to a lower-case type name that does not exist); queries that filter on an enum value use native SQL.
- **Timestamps stay UTC in `TIMESTAMP(3)`.** Hibernate writes them with `hibernate.jdbc.time_zone=UTC`; `JdbcClient` code goes through `SqlTime`, never `java.sql.Timestamp`, because JDBC would apply the JVM zone (Asia/Ho_Chi_Minh in Docker). Tests run with `-Duser.timezone=Asia/Ho_Chi_Minh`, so a mix-up fails in CI. `ClockService.now()` keeps milliseconds only, like the columns.
- **Instants on the wire look like JavaScript's `toISOString()`** (`2026-10-19T01:15:00.000Z`), via one Jackson serializer, so `lockedUntil`, `createdAt` and the dev clock read the same as before.
- **New ids** are text, "c" + base-36 time + 16 random base-36 characters (`Ids`), next to the existing cuid ids.

### Seed

- **The Java seed writes the same rows as the legacy seed.** The tree, the master data and the employee generator (the mulberry32 PRNG and the largest-remainder allocation, ported bit for bit) were checked table by table: a Java-seeded and a legacy-seeded database give identical hashes for Employee (code, name, email, org unit, meal unit, start date), OrgUnit, Setting, RoleAssignment, Floor, CostCenter, DishType, Factory, Supplier and Canteen. Only the generated ids of employees, settings and role assignments differ. `SeedDataTest` pins four employees read back from a legacy-seeded database.
- **How to run it:** `pnpm --filter @meal/api db:seed`, `java -jar meal-api.jar seed`, or `SEED_ON_START=true`. It refuses to run in production, like the legacy one. The root `pnpm db:seed` still runs the legacy seed until J3 (both give the same data).

### Sign-in details

- **argon2 hashes are compatible both ways:** `PinHasher` uses the legacy parameters (16-byte salt, 32-byte hash, 4 lanes, 64 MiB, 3 passes) in the same PHC string. The Java side verifies a hash written by Node (`PinHasherTest`) and Node verified one written by Java.
- **Failed PIN attempts are committed even though the call ends in an error** (`@Transactional(noRollbackFor = ApiException.class)`), otherwise the lock after 5 attempts would never trigger.
- **`/api/dev/clock` exists only when fake time is allowed** (`FakeTimeAllowedCondition`), so it answers 404 elsewhere, as the legacy API did. It stays system-admin only and audited.
- **Status codes follow the legacy API,** including the ones NestJS chose by default: `POST /api/system/roles` answers 201, the other POSTs 200 or 204.

### OpenAPI

- **springdoc serves `/api/openapi.json`** when `API_DOCS_ENABLED=true` (`.env.example` and the Compose `api-java` service turn it on for development; off by default and in production). It is public like the legacy health route; it describes routes, never data. Generating the web's types from it is J3.

### Tests

- 140 Java tests: the rules (57), the legacy e2e suites for auth, RBAC, security and the seed, the employee finder and the role screens, the DMP mock and HTTP clients, the settings, and the OIDC flow. Every legacy P0 test has a Java equivalent; the BR-03 fake-time test from `clock.service.spec.ts` moved next to `regularCloseAt`.
- The `/system` role test checks access with `/api/system/sync-runs` instead of `/api/admin/settings`, which arrives in J2.
- **Checked with the real web app:** the Java API on port 3001 behind `next dev`, driven by Playwright: forced PIN change and sign-in with the new PIN, HC admin employee list, dept admin scope (837 employees, all under Xưởng Chế tác), system admin through the mock Microsoft page with the roles and sync-log screens. No 5xx answer.
