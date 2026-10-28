# MVP Plan – PNJP Meal Ordering System

Oct 19, 2026 · @Quang · v1.2, aligned with BRQ-MEAL-001 v1.0

**Changes in v1.2 (Oct 28, 2026): backend on Java**

- Company standard: the API moves from NestJS (TypeScript) to Java 25 + Spring Boot 4, built with Maven. The web app stays on Next.js. No business rule changes.
- The stack (6.1), the repo layout (6.2) and the agent rules (7.1) name the Java tools. Business rules live in `apps/api/meal-rules`.
- P0–P2 were built on NestJS first. Phases J0–J3 (after P2, before P3) port them to Java with the same HTTP contract and database schema, then remove the TypeScript API.
- The code moved to the `meal-ordering-platform` repository with its full history.

**Changes in v1.1, aligning with BRQ-MEAL-001 v1.0**

- No portion limit per employee per day (BR-20), per MEAL-PLAN\_WEEK.05.
- Weekly registration closes at 23:59 Thursday instead of 17:00 (BR-03), per MEAL-PLAN\_WEEK.07.
- After the weekly close, employees may still add meals until 08:00 on day D, including employees without a forecast (BR-21, BR-23, BR-25), per MEAL-ACTUAL\_DAILY.04 and .09. Assumption A4 is withdrawn.
- The HC admin may adjust a finalized day before 10:30, with a reason and caterer confirmation, without changing the final-count snapshot (BR-43, BR-46), per the BRQ case "Đã chốt với nhà cung cấp" and MEAL-REPORT.14.
- New master data: canteen, floor and cost center mapped onto a 6-level org tree (BR-15, BR-61 to BR-64), per MEAL-MASTERDATA-01 and MEAL-ATTENDANCE.02. They enter the schema in P0 and the admin screens in P1.

## 1. Overview & MVP scope

The MVP replaces the Excel-and-paper lunch ordering process at the PNJP Go Vap factory. Employees register a week ahead, the system cancels meals for absent employees using DMP attendance data, and the HC admin finalizes the count at 08:30 and exports a file for the caterer.

**Context.** PNJP has about 1,700 employees: office staff, production workers, goldsmiths, security guards, cleaners. The company pays for lunch ordered from an outside caterer; Go Vap currently has 1 caterer. Production workers may not use phones on site, and the app runs on the intranet only.

**Problems to solve.**

- The caterer prepares more meals than are eaten because of sick days, leave and unplanned business trips; the factory absorbs the waste every day.
- Admins consolidate many Excel files and paper forms by hand, reconciliation with the caterer is hard, and meal counts are often disputed.

**Measures.** Gap between forecast and final count, gap between final count and meals received, leftover meals per unit, cost in money (final count × unit price; waste = leftovers × unit price).

**In MVP scope**

- 1 factory, 1 caterer, lunch only. The data model still supports several factories, caterers and meals.
- Sign-in with Microsoft Entra ID SSO and with employee code + PIN.
- Dish library, weekly menu building and publishing.
- Next-week registration with no portion limit; per-day add, change or remove until 08:00 on day D, also for employees without a forecast; view of colleagues' meals in the same unit.
- Bulk-entry grid and printable paper forms for dept admins.
- Sync of employees, a 6-level org structure, attendance and leave flags from DMP; mapping of floor, canteen and cost center onto the org tree, with Excel import and export.
- Provisional and final cancellation by attendance, exception handling, reconciliation, finalization, Excel export, HC admin adjustments after finalization.
- Delivery confirmation, daily/weekly/monthly reports, audit log.

**Out of MVP scope**

- Overtime dinner, pending clear business rules.
- Meal feedback and ratings.
- Internet access, a mobile-first UI for workers, QR sign-in, kiosks.
- Auto-picking dishes by preference, several caterers per factory, email or Teams notifications.
- Changing DMP source code or writing data back to DMP.

**Glossary**

| Term | Meaning |
| --- | --- |
| Meal (suất) | One portion set: rice, main dish, soup, vegetables… Employees choose by main dish |
| Meal type | Vegetarian (Chay), Porridge (Cháo) or Savory (Mặn) |
| Meal session | Lunch in the MVP; overtime dinner later |
| Week N | The week being served, starting Monday |
| Forecast | Immutable snapshot of registrations at the weekly registration close |
| Actual | Registrations after per-day changes and attendance, up to finalization |
| Final count | The count sent to the caterer at 08:30 on day D; the snapshot never changes, later changes go through adjustments |
| Adjustment | A change to a finalized day made by the HC admin with a reason and caterer confirmation (BR-46) |
| Effective count | Final count plus all adjustments; used for delivery, cost and reports |
| Meal unit | A node in the org tree that receives one shared meal box |
| Canteen (Nhà ăn) | Where meals are served; menus are built per canteen. Go Vap has 1 in the MVP |
| Floor (Tầng) | Delivery location of a meal unit's box |
| Cost center | Accounting unit that bears an employee's meal cost |
| Dept admin | Department admin, manages one or more assigned units |
| HC admin | Administration (Hành chánh) department admin, manages the whole factory |
| System admin | IT administrator |
| DMP | Internal system providing HR, attendance and leave data |
| Caterer (NCC) | Outside meal supplier; not a user of the system |

## 2. Business rules

Each served week passes four checkpoints: menu publish, registration close at 23:59 Thursday of the previous week, change lock at 08:00 on day D, finalization at 08:30 on day D. Every checkpoint is a per-factory setting, evaluated in Asia/Ho\_Chi\_Minh time and enforced on the server. Each rule has a BR code so agents can cite it in code and tests.

&#91;embedded content: week N cycle · 4 checkpoints before the week, 5 on day D\]

The top row runs once per served week; the bottom row repeats on every serving day D of week N.

### 2.1 Weekly cycle and deadlines

- **BR-01** The menu for week N can be built from 00:00 Friday of week N-2, right after week N-1's registration closes; in practice it may slip to Monday–Tuesday of week N-1.
- **BR-02** Registration for week N opens as soon as its menu is published. Employees never see an unpublished menu.
- **BR-03** Registration closes at the end of Thursday of week N-1 (closeAt). The UI shows 23:59; closeAt is stored as 00:00 Friday, exclusive, so a save at 23:59:59 is accepted. A menu published late but before this time does not extend the close.
- **BR-04** If the menu is still unpublished at the regular closeAt, closeAt = publish time + 24 hours, computed at publish.
- **BR-05** If closeAt under BR-04 falls after 08:00 of a serving day, that day still locks at its own 08:00.
- **BR-06** From 12:00 Monday of week N-1, if week N's menu is unpublished, the system warns the HC admin on every screen until it is published.
- **BR-07** At closeAt the system stores an immutable forecast snapshot and enables the weekly forecast export. Registrations stay editable per day under BR-23; every later change is measured against this snapshot. Nothing is deleted; "reset" only means the UI rolls over to the next week.
- **BR-08** Serving days default to Monday–Friday. The HC admin turns Saturday on per week and marks holidays as "not served".

### 2.2 Menu

- **BR-10** Main-dish library: name (required), type Vegetarian/Porridge/Savory (required), applicable meal sessions (required, multi-select; the MVP uses lunch only). Dessert library: name (required). Both libraries are reused across weeks; retired dishes are archived, never deleted.
- **BR-11** The HC admin drags main dishes and desserts onto each day of the week and saves. Desserts belong to the day and are display-only.
- **BR-12** A meal's display name is "\[Type\] \[Dish name\]", e.g. "Mặn Cá kho tộ".
- **BR-13** The usual mix is 1 Vegetarian + 1 Porridge + 2–3 Savory. A different mix only warns; it never blocks publishing.
- **BR-14** Editing a published menu (usually because the caterer changes a dish): replacing a dish moves its registrations to the replacement and shows employees an in-app notice; removing a dish sets its registrations to "Needs reselection", and they are cancelled if still unselected at the lock.
- **BR-15** Menus are built per canteen and week. Employees see the menu of their meal unit's canteen (BR-63).

### 2.3 Registration

- **BR-20** No portion limit (BRQ MEAL-PLAN\_WEEK.05). Per employee, per day, per meal session, an employee may pick any dishes on that day's menu, several dishes and several types, each with a quantity that is a non-negative integer. Zero or no row means not registered. The company pays for all of them. The check stays a function in the rules module (`apps/api/meal-rules`), so a cap could later be added as a setting without touching callers.
- **BR-21** No registration means no meal. Dept admins see a "not registered" list from the moment registration opens and may register on behalf of employees until 08:00 on each day D.
- **BR-22** Before closeAt, employees add, change and remove freely; the result at closeAt is the forecast.
- **BR-23** From closeAt to 08:00 on day D, employees may still add, change and remove meals for day D, including employees who had no forecast (BRQ MEAL-ACTUAL\_DAILY.04, .09). The forecast snapshot never changes; every difference shows in reconciliation (BR-41).
- **BR-24** After 08:00 on day D, employees cannot change anything. Changes are made by admins, with a reason, until finalization.
- **BR-25** Meals an admin adds after 08:00 on day D and before finalization (new hires, forgotten registrations…) are flagged "Extra" and require a reason. Meals added after finalization follow BR-46.
- **BR-26** Shortcuts: "Same as last week" copies each day's types and quantities, picking the first dish of the same type; "Apply to whole week" copies one day's selection to the other days by type. Users review before saving.
- **BR-27** Viewing colleagues in the same meal unit shows only name, dish, quantity and eating/not eating; never the reason for absence or leave.

BR-20 examples with a menu of Mặn Cá kho, Mặn Thịt kho, Chay, Cháo:

| Selection | Valid | Reason |
| --- | --- | --- |
| 3 Mặn Cá kho | Yes | No portion limit |
| 1 Chay + 1 Cháo + 1 Mặn Cá kho + 1 Mặn Thịt kho | Yes | No portion limit |
| 0 of every dish | Yes | Not registered |
| −1 Mặn Cá kho | No | Quantity must be non-negative |
| 1.5 Chay | No | Quantity must be an integer |
| A dish not on that day's menu | No | Must be a published menu item of that day and canteen |

### 2.4 Attendance and cancellation on day D

- **BR-30** The system pulls attendance from DMP every 5 minutes from 07:00 until finalization. Present = at least one record with a check-in on day D no later than 08:10.
- **BR-31** At 08:10, employees with meals who are not present become "Provisionally cancelled" for all their meals in that session.
- **BR-32** Between 08:10 and finalization, if a record with check-in ≤ 08:10 arrives late, the meals are restored automatically. A check-in after 08:10 does not restore; the employee contacts an admin.
- **BR-33** A dept or HC admin confirms presence (forgot to check in, checked in after 08:10) until finalization, with a required reason; the meals are restored.
- **BR-34** Safety check: if the latest sync failed, or fewer than 50% of employees with meals are present, the system does not cancel in bulk, keeps all meals and warns the HC admin. The HC admin re-runs the sync or cancels manually.
- **BR-35** Leave only shows a "Leave request" flag (full day, morning or afternoon); it never cancels meals. Half-day leave resolves through BR-30: morning leave loses lunch, afternoon leave keeps it.
- **BR-36** Employees who leave the company per DMP lose all meals from their end date. New hires are registered by dept admins under BR-25.

### 2.5 Reconciliation and finalization

- **BR-40** The reconciliation screen for day D shows total forecast, total actual, difference and number of cases to review; plus a per-meal table of forecast, actual, difference and status Matched or Mismatched.
- **BR-41** Cases to review: provisionally cancelled or cancelled for absence; self-reported not eating or reduced; added or increased by the employee after closeAt, including employees without a forecast; dish changed from forecast; extra; presence confirmed by an admin; on leave but still has meals. Each row shows code, name, unit, forecast meal, actual meal and case type.
- **BR-42** Warn when a meal's actual count deviates more than 10% from its forecast.
- **BR-43** At 08:30 the HC admin presses "Finalize": provisional cancellations become cancellations, the system stores a final-count snapshot by unit × meal and locks the day. Normal edits are blocked after finalization; only HC admin adjustments under BR-46 remain. If not finalized by 08:45, the HC admin is reminded.
- **BR-44** Final Excel file: rows are meal units grouped by canteen and floor, with floor columns; columns are meals, cells are counts, with a total row and a total column; plus a box-label sheet with one label per unit showing unit, floor and counts. The weekly forecast file uses the same layout, one sheet per day.
- **BR-45** The HC admin sends files to the caterer outside the system; the caterer is not a user.
- **BR-46** Adjustments after finalization (BRQ case "Đã chốt với nhà cung cấp", MEAL-REPORT.14). The system never adds anything to a finalized order by itself. The HC admin may record an adjustment for day D until `close.adjustmentDeadline` (10:30) and before the final delivery confirmation: lines of employee, meal and a positive or negative quantity, a required reason, and a required "Caterer confirmed" tick when any line adds meals. FinalCount never changes; effective count = FinalCount + sum of adjustment lines. Delivery, cost and reports use the effective count and still show the original. Each adjustment exports a supplementary Excel file in the BR-44 layout with only the changed cells.

### 2.6 Delivery

- **BR-50** The caterer delivers the effective count (BR-46) ±5%, measured on the session total for the day. Deviations outside the band are flagged red.
- **BR-51** Dept admins confirm per unit: meals received, leftovers, optional note, by 15:00. After the deadline the system records "Received in full" with received = effective count and leftovers blank.
- **BR-52** The HC admin confirms delivery for the whole factory, after which the day's data is locked.
- **BR-53** Cost = effective count × caterer unit price; waste = leftovers × unit price.

### 2.7 Org structure, meal units and audit

- **BR-60** Every node in the org tree has a "meal unit" flag set by the HC admin. An employee belongs to the nearest flagged node at or above their own node; if none exists, they cannot register and appear on an "unassigned" list.
- **BR-61** The org tree has up to 6 levels (BRQ MEAL-ATTENDANCE.02). Each node stores its level (1–6) and the DMP level name. Employees attach to their deepest node; screens show the full path.
- **BR-62** Canteen, floor and cost center are master data managed by the HC admin: add, edit, deactivate, never delete. The HC admin maps any org node to a floor, a canteen and a cost center; a node without a value inherits from the nearest ancestor that has one. If DMP supplies a cost center, it is the default and an admin mapping overrides it.
- **BR-63** Floor and canteen used for menus, counts, delivery and box labels come from the employee's meal unit. A meal unit that resolves no floor or no canteen is invalid, and its employees appear on the "unassigned" list (BR-60). Cost center is resolved per employee: the employee-level override if set, else the inherited value. Employees without a cost center can still register and appear on a "missing cost center" list.
- **BR-64** Mapping can be imported from an Excel template (org node code or employee code → floor, canteen and cost center codes). The import validates every row and shows a preview; one invalid row rejects the whole file. Export uses the same template. Every mapping change is audited (BR-70) and never rewrites past data: forecast lines, final lines and adjustment lines store the meal unit, floor, canteen and cost center in effect when they were written.
- **BR-70** The audit log records every admin action, every change after closeAt, every automatic provisional cancellation and restore, menu publishing and edits, finalization and delivery: who, when, before, after, reason.

## 3. Permissions

Four roles stack on one employee account: admins still order their own meals like any employee. Every admin permission is tied to a scope, and the API checks both role and scope; the UI only hides buttons.

| Role | Who | Scope | Assigned by |
| --- | --- | --- | --- |
| Employee | Every active employee per DMP | Self and own meal unit | Automatic on sync |
| Dept admin | Department secretaries, team tablet holders | Assigned org-tree nodes, including all child nodes | HC admin or system admin |
| HC admin | Administration department | Whole factory | System admin |
| System admin | IT | Whole system | First one from an environment variable, then other system admins |

**Function matrix.** "Units" = within assigned scope; "Factory" = whole factory; "View" = read-only.

| Function | Employee | Dept admin | HC admin | System admin |
| --- | --- | --- | --- | --- |
| View menu; register and edit own meals while open | ✓ | ✓ | ✓ | ✓ |
| View colleagues' meals today | View, own unit | Units | Factory | – |
| Register and edit on behalf before the lock; print paper forms | – | Units | Factory | – |
| Add extra meals after 08:00 until finalization (with reason) | – | Units | Factory | – |
| Confirm presence; edit from 08:00 to finalization (with reason) | – | Units | Factory | – |
| Not-registered list, exceptions list | – | Units | Factory | – |
| Dish library; build, publish, edit menu | – | – | ✓ | – |
| View reconciliation | – | Units | Factory | – |
| Finalize; export final and weekly forecast files | – | – | ✓ | – |
| Adjust a finalized day; export supplementary file (BR-46) | – | – | ✓ | – |
| Confirm delivery per unit | – | Units | Factory, on behalf | – |
| Final delivery confirmation | – | – | ✓ | – |
| Reports and report export | – | Units | Factory | – |
| Reset PIN | – | Units | Factory | ✓ |
| Assign dept admins; set meal-unit flags; manage canteens, floors, cost centers and their mapping, including Excel import | – | – | ✓ | ✓ |
| Business settings: deadlines, serving days, caterer, unit price, thresholds | – | – | ✓ | ✓ |
| Re-run attendance sync; view sync logs | – | – | ✓ | ✓ |
| View audit log | – | – | Factory | ✓ |
| Assign HC admins and system admins; configure DMP and SSO integration | – | – | – | ✓ |

**Implementation rules.**

- Permissions are checked as a (role, action, scope) triple by one authorization component of the API (Spring Security, an annotation on each endpoint), not as scattered checks inside handlers.
- Every list query by a dept admin is filtered by scope; requests for employees outside scope return 403.
- Any action after a lock requires a reason and writes to the audit log (BR-70).

## 4. UI flows

The UI is desktop-first for office staff, but dept-admin screens must work well on tablets from 768 px, because team tablet holders enter data for production workers. All UI copy is in Vietnamese; routes are in English.

**Shared layout.** The navigation has a "Mine" group for everyone and an "Admin" group for users with an admin role. A banner area at the top shows warnings (menu not published, attendance sync failed, day not finalized).

### 4.1 Screens

| Route | Screen | Roles | Main content and actions |
| --- | --- | --- | --- |
| /login | Sign in | Everyone | "Sign in with Microsoft" button; employee code + 6-digit PIN form; temporary lock after 5 wrong attempts |
| /change-pin | Change PIN | Employee | Required at first sign-in and after a PIN reset |
| /today | Today | Employee | Forecast and actual meals, attendance status, leave flag, dessert, countdown to 08:00; Add meal, Change dish and Not eating today buttons (BR-23); read-only after 08:00 with a hint to contact an admin |
| /week | Weekly registration | Employee | One card per day, a quantity stepper per meal from 0 with no cap; closeAt deadline banner (23:59 Thursday); Same as last week and Apply to whole week buttons; after closeAt each day stays editable until its own 08:00 and shows its difference from the forecast (BR-23) |
| /team | My unit | Employee | Colleagues in the same unit today: name, meal, status; search by name or code |
| /history | History | Employee | Past days: forecast, actual, status |
| /admin/today | Today (admin) | Dept admin, HC admin | Default filter: today, lunch, the user's units; search by name or code; inline edit while allowed; confirm presence through a reason dialog; Exceptions tab |
| /admin/registrations | Weekly registration (admin) | Dept admin, HC admin | Progress by unit; not-registered list; bulk-entry grid |
| /admin/print | Print forms | Dept admin, HC admin | Pick week and unit; one table per day, rows are employees, columns are meals, blank cells for counts; printed with browser print CSS |
| /admin/dishes | Dish library | HC admin | Main dishes and Desserts tabs; add, edit, archive; filter by type |
| /admin/master | Canteens, floors, cost centers | HC admin | Three tabs; add, edit, deactivate (BR-62) |
| /admin/menu | Weekly menu | HC admin | Canteen picker; library on the left, day grid on the right; drag main dishes and desserts; enable Saturday; mark non-serving days; mix warning; Save draft and Publish, showing the expected closeAt |
| /admin/reconciliation | Reconciliation | Dept admin (view), HC admin | Summary cards; per-meal table; filterable exceptions list; attendance sync status and re-run button; Finalize button; Export Excel; Weekly forecast tab; Adjustments tab after finalization (BR-46) |
| /admin/delivery | Delivery | Dept admin, HC admin | Final count per unit; enter received, leftovers, note; flag outside ±5%; HC admin final confirmation |
| /admin/reports | Reports | Dept admin, HC admin | Day, week, month: forecast, final, adjustments, effective, received, leftovers, cost; filter and group by canteen, floor, cost center and org level; Excel export |
| /admin/org | Org structure | HC admin | 6-level tree from DMP; set meal-unit flags; map floor, canteen and cost center with inherited values shown; assign dept admins; unassigned and missing-cost-center lists; Excel import and export of mapping (BR-64) |
| /admin/employees | Employees | Dept admin, HC admin | Find and filter by org path, floor, canteen, cost center; see meal unit and roles; set cost-center override; reset PIN |
| /admin/settings | Settings | HC admin | Deadlines, serving days, caterer and unit price, warning thresholds |
| /admin/audit | Audit log | HC admin | Filter by date, person, entity |
| /system/\* | System | System admin | Role assignment; DMP and SSO integration status; sync logs |

**Bulk-entry grid.** Pick the day with a tab; rows are employees, columns are that day's meals, cells take a non-negative integer. The layout matches the printed form exactly so data is entered line by line. Tab and Enter move between cells, invalid cells show on the row, and Save writes the whole batch.

### 4.2 Main journeys

**J1 – Office employee registers for the week**

1. Sign in with Microsoft and open /week; the week currently open for registration is preselected.
2. Pick meals day by day, or use Same as last week and adjust.
3. Save; the screen confirms the meal count and the closeAt deadline.

**J2 – Production workers register on paper**

1. When the menu is published, the dept admin prints forms for the teams in scope.
2. Workers write meal counts on the form; the dept admin collects them before the end of Thursday (closeAt 23:59).
3. The dept admin enters the grid day by day and saves.
4. The dept admin checks the not-registered list and resolves it before closeAt.

**J3 – Morning of day D**

1. From 07:00, the system syncs attendance every 5 minutes.
2. Until 08:00, employees add meals, change dishes or report not eating; workers tell their team's tablet holder.
3. At 08:10, meals of employees not yet present become provisionally cancelled.
4. Dept admins work the exceptions list: confirm presence, edit meals with a reason.
5. At 08:30, the HC admin reviews reconciliation, presses Finalize, exports Excel and sends it to the caterer.
6. If meals are still needed or must be removed after finalization, the HC admin checks with the caterer, records an adjustment before 10:30 (BR-46) and sends the supplementary file.

**J4 – After lunch**

1. Dept admins enter received, leftovers and notes per unit before 15:00.
2. The HC admin reviews the summary, checks the ±5% band and confirms delivery.

**J5 – Build the weekly menu**

1. The HC admin adds new dishes to the library if needed.
2. Drag dishes onto each day, enable Saturday or mark holidays if needed.
3. Press Publish; the system computes closeAt under BR-03 or BR-04 and opens registration.

## 5. DMP integration

The system only reads data from DMP through its existing REST API, by scheduled pulls; it never changes DMP code or writes back. Every call goes through an adapter with a mock implementation, so phases that run before a service account exists are still complete. Source: the dmp-backend survey, branch main, commit ab4b9357.

| Data | Endpoint | Key parameters | Schedule | Notes |
| --- | --- | --- | --- | --- |
| Service token | POST /api/auth/login | email, password of the LDAP service account | On startup, on token expiry or on a 401 | JWT without refresh token; the account needs an unscoped role (it, hr or planner) |
| Employees | GET /api/hr-employees | page, size ≤ 500, sort=employeeId,asc | Daily at 02:00 and on manual trigger | Returns a Spring Page (content, totalElements), no wrapper. Store only employeeId, name, email, unit codes, cost center code if present, leaveStatus, startWDate; drop birth date, phone number, gender |
| Org structure | GET /api/hierarchy | none | Daily at 02:00 | Returns the full tree, unpaged; fallback /api/dropdown/cctc/\*. Map DMP levels to levels 1–6 (BR-61); check whether nodes carry a cost center code |
| Attendance | GET /api/attendance | fromDate, toDate as dd-MM-yyyy; companyCode; page; size | Every 5 minutes from 07:00 until finalization | No time filter: fetch the whole day, then keep check-ins ≤ 08:10. One person can have several rows; use the earliest check-in |
| Leave | GET /api/daily-forecast | fromDate, toDate as ISO; hasLeave=true; companyCode; page; pageSize | Minute 30 of every hour, window today to 14 days ahead | Use restStatus, leaveTypeName, leaveDayType (FULL, AM, PM); flags only, per BR-35 |
| Overtime | Not used |  |  | Out of MVP. DMP has 3 overtime sources, none official, none with an approval status |

**Do not use.**

- /api/employee-view: returns unit names inside code fields and has no email.
- /api/hr-employees/search/medic: requires no sign-in; the system must not rely on this hole.
- The dateAttendanceForecast field and the hr\_attendance\_forecast table: an assumed 07:30 check-in, not real attendance.
- POST /api/hr/attendance/generate-data: generates fake attendance; never call it in any environment.

**Adapter rules.**

- One DmpClient interface with two implementations, HttpDmpClient and MockDmpClient, selected by DMP\_MODE=http|mock. The mock reads scenarios from a seed file: on time, late, forgot to check in, morning leave, afternoon leave, API error, API returning too little data.
- Fetch pages sequentially, size at most 500, 10-second timeout, 3 retries with backoff. No unscheduled calls in the 07:30–08:30 window unless an admin presses re-run.
- Every sync writes a SyncRun: type, start, end, record count, error. Data is written only when the whole run succeeds, upserted by employee code and date.
- The employee code is employeeId in the employee and attendance APIs and employeeCode in daily-forecast; same value, normalized to employeeCode in this system.
- Date-time parsing lives in separate, tested functions: dd-MM-yyyy, ISO, and LocalDateTime without offset, treated as Vietnam time until verified with real data.
- Before P3, download DMP's /v3/api-docs to check response shapes, since Swagger lacks field descriptions.
- Service account credentials live only in environment variables, never committed.

**SSO mapping.** The email claim in the Entra id\_token (fallback preferred\_username) is matched case-insensitively to the employee email from DMP; DMP also matches Entra users by email. No match shows an "Account not linked" screen with instructions to contact the HC admin; a system admin can link the account manually.

## 6. Architecture & data model

A monorepo holds a Next.js web app, a Java (Spring Boot) API with a separate pure business-rules module, and a small TypeScript package the web app uses for types and UI helpers. Everything runs in Docker on PNJ's internal infrastructure behind a same-domain reverse proxy. Deadlines are checked on every request; background jobs only do proactive work such as syncing, snapshots and reminders.

&#91;embedded content: architecture · 4 internal components, DMP and Entra ID\]

The browser reaches both the web app and /api through one intranet domain; only the API talks to DMP and Entra ID.

### 6.1 Chosen stack

| Layer | Choice | Notes |
| --- | --- | --- |
| Monorepo | pnpm workspaces + Turborepo; Maven (wrapper) for the API, run by turbo | apps/web, apps/api, packages/shared |
| Web | Next.js App Router, TypeScript, Tailwind CSS, shadcn/ui, Motion | Calls the API through same-domain /api with a fetch wrapper + TanStack Query; no Axios |
| Forms | React Hook Form + Zod | No Formik. Zod only checks forms in the browser; the API validates everything again |
| State and drag-and-drop | Zustand only for the menu builder; dnd-kit |  |
| API | Java 25 (LTS), Spring Boot 4 (Spring MVC on virtual threads), PostgreSQL 17 | Not WebFlux, no Java EE application server |
| Database access | Flyway migrations; Spring Data JPA (Hibernate) for CRUD; `JdbcClient` for snapshots, bulk upserts and reports |  |
| Validation and contract | Jakarta Bean Validation on record DTOs; springdoc-openapi, with web types generated by openapi-typescript | No Lombok, no MapStruct |
| Sign-in | Nimbus OAuth 2.0 SDK (the library under Spring Security's OAuth2 client): Entra OIDC Authorization Code + PKCE; own Postgres-backed sessions via httpOnly cookie | PINs hashed with argon2id (`Argon2PasswordEncoder` from spring-security-crypto), compatible with the legacy hashes |
| Authorization | `@RequirePermission(action, scope)` on every endpoint, checked by one Spring MVC `HandlerInterceptor` (`AccessInterceptor`); a route without a declaration is denied |  |
| Background jobs | Spring `@Scheduled` + ShedLock on Postgres | No Redis, no job queue |
| DMP client | Spring `RestClient`; 10 s timeout, 3 attempts with backoff, one re-login on 401 |  |
| Excel | Apache POI |  |
| Tests | JUnit 5, AssertJ, Spring Boot Test + MockMvc, embedded PostgreSQL (no Docker needed), ArchUnit for the API; Vitest for shared and web; Playwright for e2e | No Storybook in the MVP |
| Code style | Spotless with google-java-format for Java; ESLint and Prettier for TypeScript |  |
| Infrastructure | Docker Compose for dev; Docker on PNJ infrastructure (the API image carries its own Java 25 runtime); nginx: / to web, /api to api | Intranet only |

### 6.2 Repo layout

```text
meal-ordering-platform/
  AGENTS.md                 agent rules (section 7.1)
  docs/                     plan.md (this document), decisions.md, open-questions.md, security.md
  apps/
    web/                    Next.js: (employee)/today, week, team, history; admin/*; system/*
    api/                    Java 25 + Spring Boot, Maven
      meal-rules/           pure business rules (BR-xx): deadlines, portions, meal-unit and mapping
                            resolution; no Spring, no database, no wall clock
      meal-api/             vn.pnj.meal.api: auth, org, employees, menu, registrations, attendance,
                            reconciliation, delivery, reports, settings, audit, dmp, jobs, seed
        src/main/resources/db/migration/   Flyway migrations
    api-legacy/             NestJS API of P0–P2, removed at the end of J3
  packages/
    shared/                 used by the web only: types, enums, UI helpers
  docker/                   docker-compose.yml, Dockerfiles, nginx.conf
```

### 6.3 Data model

"Actual" is not stored separately: it is computed from active registrations and day-D presence, and a snapshot is stored at finalization.

| Entity | Key fields | Notes |
| --- | --- | --- |
| Factory | code, name, dmpCompanyCode, timezone | 1 row in the MVP |
| Setting | factoryId, key, value | Defaults in 6.4 |
| Supplier | factoryId, name, unitPrice, active | Unit price used in reports |
| Canteen | factoryId, code, name, supplierId, active | 1 row in the MVP; BR-62 |
| Floor | factoryId, code, name, sortOrder, active | BR-62 |
| CostCenter | factoryId, code, name, source, active | source: DMP or MANUAL; BR-62 |
| OrgUnit | factoryId, dmpCode, name, level, dmpLevelName, parentId, path, isMealUnit, floorId, canteenId, costCenterId, active | level 1–6 (BR-61); floorId, canteenId, costCenterId are nullable and inherited from ancestors (BR-62) |
| Employee | employeeCode, name, email, orgUnitId, mealUnitId, costCenterOverrideId, dmpCostCenterCode, status, startDate, endDate, pinHash, pinMustChange, pinFailedCount, lockedUntil | mealUnitId recomputed on sync or flag change (BR-60); floor and canteen come from the meal unit, cost center from the override or inheritance (BR-63) |
| RoleAssignment | employeeId, role, factoryId, orgUnitId | role: DEPT\_ADMIN, HC\_ADMIN, SYS\_ADMIN; orgUnitId only for DEPT\_ADMIN |
| Dish | factoryId, name, type, sessions, archivedAt | type: CHAY, CHAO, MAN |
| Dessert | factoryId, name, archivedAt |  |
| MenuWeek | factoryId, canteenId, weekStart, status, publishedAt, closeAt, closedAt | Unique on (canteenId, weekStart); status: DRAFT, PUBLISHED, CLOSED |
| MenuDay | menuWeekId, date, isServing |  |
| MenuItem | menuDayId, session, dishId, sortOrder | One selectable meal; session is LUNCH only in the MVP |
| MenuDayDessert | menuDayId, dessertId |  |
| Registration | employeeId, menuItemId, date, session, quantity, source, isExtra, extraReason, status | Unique on (employeeId, menuItemId); status: ACTIVE, NEEDS\_RESELECT, CANCELLED |
| ForecastLine | menuWeekId, employeeId, date, session, menuItemId, quantity, mealUnitId, floorId, canteenId, costCenterId | Immutable forecast snapshot with the mapping in effect at closeAt (BR-07, BR-64) |
| DailyPresence | employeeId, date, firstCheckInAt, state, confirmedById, confirmReason | state: UNKNOWN, PRESENT, PROVISIONAL\_ABSENT, ABSENT |
| LeaveFlag | employeeId, date, dayType, leaveTypeName, syncedAt | dayType: FULL, AM, PM |
| DayClose | factoryId, date, session, status, finalizedById, finalizedAt | status: OPEN, FINALIZED |
| FinalEmployeeLine | dayCloseId, employeeId, menuItemId, quantity, mealUnitId, floorId, canteenId, costCenterId | Per-employee snapshot at finalization; source for cost by cost center |
| FinalCount | dayCloseId, mealUnitId, floorId, canteenId, menuItemId, quantity | Sum of FinalEmployeeLine by unit × meal; write-once |
| Adjustment | dayCloseId, createdById, createdAt, reason, catererConfirmed | BR-46 |
| AdjustmentLine | adjustmentId, employeeId, menuItemId, delta, mealUnitId, floorId, canteenId, costCenterId | delta may be negative; effective count = FinalCount + Σ delta |
| DeliveryUnit | dayCloseId, mealUnitId, receivedQty, leftoverQty, note, confirmedById, autoConfirmed | BR-51 |
| DeliveryFinal | dayCloseId, confirmedById, confirmedAt | BR-52 |
| Notification | employeeId, type, payload, readAt | In-app notices (BR-14) |
| MappingImport | fileName, uploadedById, status, rowCount, errorCount, appliedAt | Preview, then apply all or nothing (BR-64) |
| AuditLog | actorId, action, entityType, entityId, before, after, reason, at | BR-70 |
| SyncRun | type, startedAt, finishedAt, status, recordCount, error |  |
| AuthSession | employeeId, tokenHash, expiresAt, userAgent |  |

### 6.4 Default settings

| Key | Default | Rule |
| --- | --- | --- |
| registration.close | Thursday, 23:59 (stored as Friday 00:00, exclusive) | BR-03 |
| registration.latePublishHours | 24 | BR-04 |
| menu.publishWarnAt | Monday, 12:00 | BR-06 |
| servingDays | Monday–Friday | BR-08 |
| day.lockTime | 08:00 | BR-23, BR-24 |
| attendance.pollStart / pollIntervalMin | 07:00 / 5 | BR-30 |
| attendance.cutoff | 08:10 | BR-30, BR-31 |
| attendance.safetyMinPresentPct | 50 | BR-34 |
| reconciliation.dishDeviationPct | 10 | BR-42 |
| close.time / close.reminderAt | 08:30 / 08:45 | BR-43 |
| close.adjustmentDeadline | 10:30 | BR-46 |
| delivery.tolerancePct | 5 | BR-50 |
| delivery.confirmDeadline | 15:00 | BR-51 |
| pin.maxAttempts / pin.lockMinutes | 5 / 15 | PIN sign-in |

### 6.5 Background jobs

Every job is idempotent per (factory, date). If a job is missed, the first request after the checkpoint performs the missing work (for example, creating the forecast snapshot).

| Job | Schedule (Vietnam time) | Work |
| --- | --- | --- |
| sync-org-employees | Daily at 02:00 | Sync org tree and employees; recompute meal units and inherited floor, canteen, cost center (BR-60, BR-62, BR-63); cancel meals of leavers (BR-36) |
| sync-leave | Minute 30 of every hour | Leave flags from today to 14 days ahead |
| poll-attendance | Every 5 minutes from 07:00 to finalization, serving days | Update DailyPresence; auto-restore per BR-32 |
| provisional-cancel | 08:10 on serving days | BR-31 with the BR-34 safety check |
| close-week | At each week's closeAt | ForecastLine snapshot (BR-07) |
| menu-publish-warning | Monday 12:00, then hourly until published | BR-06 |
| close-reminder | 08:45 on serving days | BR-43 |
| delivery-autoconfirm | 15:00 on serving days | BR-51 |

## 7. Phases for agents

The MVP is split into 8 phases, done in order from P0 to P7, with the Java migration J0–J3 between P2 and P3. P0–P2 were first built on NestJS; their checklists below still name the tools used then. P0–P2 run fully against the DMP mock; P3 needs a DMP service account for real testing. A phase is done only when all of its exit criteria are met.

### 7.1 Agent working rules (copy into AGENTS.md)

1. Read docs/plan.md before each phase. Do exactly one phase per run; never start work belonging to a later phase.
2. Business rules are pure Java functions in apps/api/meal-rules; test names cite the BR code with @DisplayName, e.g. "BR-23: an employee with no forecast can add a meal at 07:59 on day D".
3. Never read the wall clock in business code (Instant.now(), LocalDate.now(), new Date(), System.currentTimeMillis() and the like): use ClockService in the api and pass now into rule functions. Read every deadline from Setting; never hardcode.
4. Store timestamps in UTC; convert to Asia/Ho\_Chi\_Minh in rules and UI. Store serving days as date.
5. Check permissions in the guard as (role, action, scope). Every admin endpoint has a 403 test for out-of-scope access.
6. Every change after a lock requires a reason and writes an AuditLog entry.
7. Never delete business data; use statuses or archivedAt. ForecastLine, FinalEmployeeLine and FinalCount are write-once; changes after finalization go through Adjustment (BR-46).
8. Call DMP only through DmpClient. Dev and tests use DMP\_MODE=mock; never call the real DMP in tests; never call generate-data.
9. UI copy is Vietnamese, kept in one messages file for easy editing.
10. When the spec is unclear: log it in docs/open-questions.md, take the safest option already in this plan, mark a TODO with the BR code; never invent new rules.
11. Closing a phase: lint, typecheck and tests pass; seed runs; docs/decisions.md is updated; write how to demo each exit criterion.
12. Never commit secrets; .env.example lists every variable.

### P0 – Foundation

- [ ] Monorepo with pnpm + Turborepo; ESLint, Prettier, strict TypeScript; Docker Compose with postgres, api, web, nginx.
- [ ] Core Prisma schema: Factory, Setting, Supplier, Canteen, Floor, CostCenter, OrgUnit (level 1–6 with floor, canteen and cost-center columns), Employee (with cost-center override), RoleAssignment, AuthSession, AuditLog, SyncRun. Seed 1 factory, 1 canteen, 4 floors, 10 cost centers, a 6-level org tree with partial mapping so inheritance is exercised, 1,700 fake employees, all 4 roles.
- [ ] Mapping resolution in packages/shared/rules (BR-62, BR-63), unit-tested: nearest ancestor wins, employee cost-center override, a meal unit with no floor or canteen is invalid.
- [ ] ClockService with a fake-time mode, enabled only outside production.
- [ ] Employee code + PIN sign-in: argon2, lock after 5 wrong attempts, forced PIN change at first sign-in, PIN reset issues a temporary PIN.
- [ ] Entra OIDC + PKCE sign-in and email mapping (section 5), with a mock mode until the app registration exists.
- [ ] RBAC guard, scope decorator, AuditLog service.
- [ ] Web: layout, role-based navigation, banner area, /login, /change-pin, Vietnamese messages file.
- [ ] DmpClient interface, MockDmpClient reading seed scenarios, HttpDmpClient skeleton.

**Done when:** all 4 roles can sign in; tests prove 403 on out-of-scope access; tests with fake time at Thursday 23:59:30 and Friday 00:00 give different results; mapping resolution tests pass on the 6-level seed tree; docker compose up runs the whole system.

### P1 – Org structure and menu

- [ ] Sync org tree and employees through DmpClient, writing SyncRun; sync-org-employees job; meal-unit resolution (BR-60); DMP levels mapped to 1–6 (BR-61); cost center taken from DMP when present.
- [ ] /admin/master: canteens, floors, cost centers (BR-62).
- [ ] /admin/org: set meal-unit flags; map floor, canteen and cost center with inherited values shown; assign dept admins; unassigned and missing-cost-center lists.
- [ ] Excel import and export of mapping with validation, preview and all-or-nothing apply (BR-64); every change audited.
- [ ] Main-dish and dessert libraries: add, edit, archive (BR-10).
- [ ] /admin/menu per canteen (BR-15): week grid, drag-and-drop, Saturday toggle, non-serving days, mix warning (BR-08, BR-11, BR-13).
- [ ] Publishing and closeAt calculation (BR-03 to BR-05); menu-publish-warning job (BR-06).
- [ ] Editing a published menu per BR-14, with Notification.
- [ ] /admin/settings for the keys in 6.4.

**Done when:** the closeAt function has tests for publishing on Monday, Thursday 23:59, Friday 00:01, Saturday and Sunday 20:00; the HC admin can build and publish a week; employees see their canteen's published menu and never a draft; a mapping import with one bad row is rejected with row-level errors and applies nothing; changing a mapping leaves existing snapshot rows unchanged.

### P2 – Registration

- [ ] Rules BR-20 and BR-22 to BR-24 in packages/shared, tested against the example table in 2.3.
- [ ] Registration API: save a whole week, per-day edits, server-side deadline checks.
- [ ] /week with Same as last week and Apply to whole week (BR-26); /today registration part; /team (BR-27); /history.
- [ ] /admin/registrations: progress, not-registered list, bulk-entry grid usable on a 768 px tablet; /admin/print.
- [ ] Extra meals after 08:00 until finalization (BR-25), audited.
- [ ] close-week job creating the forecast snapshot with mapping columns (BR-07, BR-64), idempotent and self-healing when missed.
- [ ] Cancel leavers' meals on sync (BR-36).

**Done when:** an e2e test with fake time walks register on Tuesday, free edits, forecast snapshot at 23:59 Thursday, then one employee adds a meal and another with no forecast registers for Monday at 07:59, full lock at 08:00 on day D, and both show as differences from the forecast; the bulk grid saves 50 employees × 5 days; the forecast snapshot is unchanged after edits.

### J0–J3 – Java migration (after P2, before P3)

The API of P0–P2 is rewritten in Java. The HTTP contract (routes, bodies, error codes, cookies) and the database schema stay the same, so the web app keeps working unchanged. Until J3 ends, `apps/api-legacy` (NestJS) serves the web; the Java API grows in `apps/api`. Each J phase is one run, like a P phase.

**J0 – Java foundation**

- [ ] Maven multi-module `apps/api` (meal-rules, meal-api), Java 25, Spring Boot 4, Maven wrapper; turbo runs lint (Spotless), typecheck (compile), test and build.
- [ ] Flyway V1–V3 copied from the Prisma migrations; baseline option for a development database created by the legacy API.
- [ ] Configuration from the `.env.example` names with the same production refusals; `DATABASE_URL` converted for JDBC.
- [ ] ClockService with fake time outside production; ArchUnit rule against reading the wall clock; meal-rules free of Spring.
- [ ] `/api` prefix, security headers, the shared error body for every error, `/api/health`.
- [ ] Tests on an embedded PostgreSQL 17 (no Docker), `TEST_DATABASE_URL` override; Java Dockerfile; Compose keeps the legacy API and adds the Java one behind a profile.

**Done when:** `mvnw verify` passes; the Java API starts on an empty database and on one seeded by the legacy API; `/api/health` answers; the ArchUnit rule fails on `Instant.now()` outside ClockService.

**J1 – P0 on Java**

- [ ] Rules of P0 in meal-rules (time, deadlines, mapping, permissions, PIN, SSO) with their tests ported.
- [ ] JPA entities for the P0 tables; Java seed with the same deterministic ids and data as the legacy seed.
- [ ] PIN sign-in (argon2id, hashes made by the legacy API still verify), sessions, Entra OIDC + PKCE and its mock, PIN change and reset.
- [ ] RBAC: `@RequirePermission` + scope resolution, fail closed; AuditLog service; first system admin; dev clock endpoint.
- [ ] DmpClient interface, MockDmpClient (same scenarios), HttpDmpClient skeleton; springdoc OpenAPI.

**Done when:** the P0 exit criteria and `docs/demo-p0.md` pass against the Java API with the existing web; every P0 test of the legacy API has a Java equivalent.

**J2 – P1 on Java**

- [ ] Org and employee sync, master data, org mapping with Excel import and export (Apache POI), dish libraries and their import, menus, publishing and closeAt, BR-14 notices, settings.
- [ ] Jobs on `@Scheduled` + ShedLock: sync-org-employees, menu-publish-warning, close-week, reselect-deadline.

**Done when:** the P1 exit criteria, `docs/demo-p1.md` and the change-request demos pass against the Java API.

**J3 – P2 on Java and switch-over**

- [ ] Registration (own, admin, bulk grid, import), forecast snapshot, team, history, leavers.
- [ ] Web types generated from OpenAPI; Compose and nginx point at the Java API.
- [ ] `apps/api-legacy`, Prisma and the API-only parts of `packages/shared` removed.

**Done when:** the P2 exit criteria, `docs/demo-p2.md` and every legacy e2e scenario pass against the Java API, and the legacy API is gone.

### P3 – Attendance and day-D processing

- [ ] HttpDmpClient: service token, /api/attendance, /api/daily-forecast for leave, date-time parsers.
- [ ] poll-attendance, provisional-cancel and sync-leave jobs; DailyPresence and LeaveFlag tables.
- [ ] Rules BR-30 to BR-35 in meal-rules, tested against every mock scenario.
- [ ] /admin/today: default filters, inline edits, presence confirmation, exceptions tab; /today shows attendance status.
- [ ] Sync status, re-run button, banner on failure.

**Done when:** with mock data, on-time employees keep meals; no check-in becomes provisionally cancelled at 08:10; a 08:05 check-in arriving at 08:20 restores automatically; a 08:15 check-in does not; an API error or under 50% present causes no cancellation and shows a warning; morning leave loses lunch, afternoon leave keeps it. Once a service account exists, call the real DMP and compare record counts with DMP.

### P4 – Reconciliation, finalization, export

- [ ] Compute actual counts, compare with forecast, exceptions list (BR-41), per-meal deviation warning (BR-42).
- [ ] /admin/reconciliation and the Finalize button (BR-43): provisional cancellations become cancellations, FinalCount stored, day locked; close-reminder job.
- [ ] Adjustments after finalization (BR-46): HC admin only, deadline from Setting, required reason and caterer tick, supplementary Excel file; FinalEmployeeLine snapshot at finalization.
- [ ] Excel export of the final file, the weekly forecast file and the box-label sheet (BR-44).

**Done when:** file totals equal FinalCount totals and the reconciliation screen; every normal edit API for a finalized day is rejected; an HC admin adjustment before 10:30 is accepted, leaves FinalCount unchanged and changes the effective count; an adjustment after 10:30 or by a dept admin is rejected; files open in Excel with correct Vietnamese text.

### P5 – Delivery

- [ ] /admin/delivery: dept admins enter received, leftovers, note; flag deviations outside ±5% (BR-50, BR-51).
- [ ] delivery-autoconfirm job at 15:00; HC admin final confirmation (BR-52).

**Done when:** journey J4 runs for a full day; units that don't confirm are recorded as "Received in full" at 15:00; nothing is editable after final confirmation.

### P6 – Reports and audit

- [ ] /admin/reports by day, week, month: forecast, final, adjustments, effective, received, leftovers, cost (BR-53), grouped by canteen, floor, cost center or org level; Excel export.
- [ ] /admin/audit with filters by date, person, entity.

**Done when:** report figures match reference queries over 4 weeks of seed data.

### P7 – Pilot

- [ ] Playwright e2e for a full week (J1–J5) with fake time.
- [ ] Load test 1,700 users in the 07:45–08:00 window with k6; proposed target p95 under 500 ms for the registration API.
- [ ] Docker images, nginx config, daily Postgres backups, operations guide.
- [ ] Complete the go-live checklist in section 8.
- [ ] UAT with 1–2 units, running in parallel with the paper and Excel process for 2 weeks.

**Done when:** after 2 weeks in parallel, the system's final counts match the old process or every difference is explained.

## 8. Assumptions & dependencies to confirm

The plan uses 13 assumptions (A4 withdrawn in v1.1) so agents are never blocked; each one is a setting or a separate rule function, so it can change without touching the architecture. There are also 11 external dependencies that must be done before their phase.

**Assumptions**

| Code | Assumption in use | Rule | Confirmed by | Before phase |
| --- | --- | --- | --- | --- |
| A1 | Meal boxes follow the "meal unit" flag on the org tree; still unclear whether production is split by line or by team | BR-60 | Administration dept | P1 |
| A2 | If a late close passes 08:00 of a serving day, that day still locks at 08:00 | BR-05 | Administration dept | P1 |
| A3 | Saturday is enabled week by week | BR-08 | Administration dept | P1 |
| A4 | Withdrawn in v1.1: BRQ lets employees add meals until 08:00 on day D | BR-23 | — | — |
| A5 | Leave only shows a flag and never cancels meals | BR-35 | DMP team | P3 |
| A6 | Safety threshold of 50% present | BR-34 | Administration dept | P3 |
| A7 | Check-in times in DMP are Vietnam local time | Section 5 | DMP team, real data | P3 |
| A8 | Warn when a meal deviates more than 10% from forecast | BR-42 | Administration dept, caterer | P4 |
| A9 | The HC admin finalizes manually at 08:30, reminded at 08:45 | BR-43 | Administration dept, caterer | P4 |
| A10 | Unconfirmed deliveries after 15:00 are recorded as "Received in full" | BR-51 | Administration dept | P5 |
| A11 | Cost = effective count × unit price | BR-53 | Administration dept, caterer contract | P6 |
| A12 | Adjustments after finalization are allowed until 10:30 | BR-46 | Administration dept, caterer | P4 |
| A13 | Floor and canteen follow the meal unit; cost center follows the employee | BR-63 | Administration dept, accounting | P1 |
| A14 | DMP hierarchy levels map onto BRQ's 6 levels | BR-61 | DMP team | P1 |

**External dependencies**

- [ ] PNJP Go Vap's company code in DMP — before P1 syncs real data.
- [ ] DMP team explains how hierarchy levels map to BRQ's 6 levels and whether nodes or employees carry a cost center code — before P1.
- [ ] Administration dept provides the canteen, floor and cost-center lists and the initial mapping file — before P7.
- [ ] DMP team provides a read-only LDAP service account with an unscoped role — before P3.
- [ ] DMP team states the delay for attendance records reaching hr\_attendance — before P3.
- [ ] DMP team confirms the leave table holds only approved requests, and the meaning of leaveStatus values — before P3.
- [ ] Caterer's meal unit price — before P6.
- [ ] IT creates the Entra ID app registration with an intranet redirect URI, and confirms HR emails match Entra UPNs — before P7 (mock until then).
- [ ] Administration dept names a dept admin for every meal unit, especially production teams — before P7.
- [ ] Internal infrastructure: Docker host, Postgres, intranet domain, backups — before P7.
- [ ] Report the survey's security findings to the DMP team: /api/hr-employees/search/medic needs no sign-in, the production config file with secrets is in the repo, the SAP API key is hardcoded.
