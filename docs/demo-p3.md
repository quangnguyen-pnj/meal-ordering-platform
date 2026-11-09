# Phase 3 – how to demo each exit criterion

The "Done when" items of plan P3 (v1.3), and exactly how to show each one. Commands run from the repository root. Setup and accounts are as in [demo-p0.md](demo-p0.md) to [demo-p2.md](demo-p2.md). P3 adds Flyway migration V6 (`pnpm db:seed` applies it).

```bash
pnpm install
pnpm db:seed
pnpm dev                    # API :3001 (jobs on: poll-attendance every minute, sync-leave hourly), web :3000
```

Accounts (PIN `123456`, change at first sign-in): employee `PNJP000001`, dept admin `PNJP000002` (Xưởng Chế tác), HC admin `PNJP000003`, system admin `PNJP000004` (sets fake time with `PUT /api/dev/clock`, demo-p0 section 3).

The mock DMP serves one scenario at a time, picked with `DMP_MOCK_SCENARIO` in `.env` (restart `pnpm dev` after a change). Every scenario acts on employees `PNJP000008`–`PNJP000020`; everyone else checks in at 07:45.

| Scenario             | What DMP returns                                                                      |
| -------------------- | ------------------------------------------------------------------------------------- |
| `on-time`            | everyone checks in at 07:45                                                           |
| `late`               | 008–020 check in at 08:15                                                             |
| `late-record`        | 008–020 check in at 08:05, but the rows only reach DMP at 08:20                       |
| `forgot-to-check-in` | 008–020 have no row                                                                   |
| `manager-confirmed`  | 008–014 have no check-in and 015–020 check in at 08:25; their manager confirms in DMP |
| `morning-leave`      | 008–020 on morning leave, check in at 13:00                                           |
| `afternoon-leave`    | 008–020 on afternoon leave, check in at 07:45                                         |
| `api-error`          | every call fails                                                                      |
| `too-little-data`    | only the first 30% of employees have a row                                            |

Automated proof of everything below:

```bash
pnpm --filter @meal/api test   # AttendanceRulesTest, DayCasesTest (BR-30..35, BR-41),
                               # MockDmpClientTest, HttpDmpClientTest (attendance, leave),
                               # AttendanceTest (every criterion with fake time, one scenario per day)
pnpm --filter @meal/web test   # admin-today-screen.test.tsx, banners.test.ts
```

`apps/api/meal-api/src/test/java/vn/pnj/meal/api/attendance/AttendanceTest.java` publishes two weeks (2029-03-05 and 2029-03-12), gives 34 employees one meal a day (`PNJP000001`, `PNJP000008`–`PNJP000040`) and runs one scenario per serving day through the same job handler the scheduler calls.

## Preparing a day by hand

1. Publish a menu for the week you will use (demo-p1), and register meals for a few of 008–020 and some others (as the employees on `/week`, or as `PNJP000002`/`PNJP000003` on `/admin/registrations`).
2. As `PNJP000004`, set fake time to that serving day before 07:00, e.g. `{"at":"2026-11-03T06:55:00+07:00"}`.
3. Move the clock forward in steps (07:00, 08:10, 08:20, …). The `poll-attendance` job runs every minute on real time and acts on the fake time, so allow up to a minute after each step, or press "Chạy lại đồng bộ" on `/admin/today` as `PNJP000003`.
4. Watch `/admin/today` as `PNJP000003` (whole factory) or `PNJP000002` (Xưởng Chế tác only), and `/today` as an employee.

## 1. On-time employees keep their meals

- **Automated:** `AttendanceTest.lateScenario` (employees outside 008–020 are `PRESENT` from 07:00 and stay so after 08:10).
- **By hand:** scenario `on-time`. After 07:00 every employee with meals shows "Có mặt" with "Vào 07:45" on `/admin/today`. At 08:10 the sync panel says "tạm hủy suất của 0 người".

## 2. No check-in becomes provisionally cancelled at 08:10

- **Automated:** `AttendanceTest.lateScenario` (13 employees `PROVISIONAL_ABSENT`, 13 `presence.provisionallyCancelled` audit entries, `AttendanceDay` = `CANCELLED`, 34 with meals, 21 present) and `confirmPresence` (`forgot-to-check-in`).
- **By hand:** scenario `forgot-to-check-in`. Before 08:10, 008–020 show "Chưa chấm công". From 08:10 they show "Tạm hủy", their meals are struck through, the "Tạm hủy" card counts them, and they head the "Ngoại lệ" tab. As one of them on `/today` (reset their PIN on `/admin/employees`): "Chưa chấm công trước 08:10: suất ăn của bạn đang tạm hủy…". Their registrations are unchanged.

## 3. A 08:05 check-in arriving at 08:20 restores automatically

- **Automated:** `AttendanceTest.lateRecordRestores` (still cancelled at 08:15, `PRESENT` at 08:20, 13 `presence.restored` entries).
- **By hand:** scenario `late-record`. At 08:10 008–020 are "Tạm hủy"; once the clock passes 08:20 and the next poll runs they show "Có mặt", "Vào 08:05", and leave the exceptions tab.

## 4. A 08:15 check-in does not restore

- **Automated:** `AttendanceRulesTest` ("BR-32: a 08:15 check-in does not restore") and `AttendanceTest.lateDoesNotRestore`.
- **By hand:** scenario `late`. At 08:10 008–020 are "Tạm hủy" with "Vào 08:15"; later polls keep them cancelled. An admin restores one with "Xác nhận có mặt" (section 6).

## 5. A manager's DMP confirmation before finalization restores, whatever the arrival time

- **Automated:** `AttendanceTest.managerConfirmed` (008–014 restored at 08:20, 015–020 at 08:25; `/admin/today` shows "QL xác nhận trên DMP" for them and no case for on-time employees) and `AttendanceRulesTest` ("BR-30: a manager's DMP confirmation is present whatever the arrival time").
- **By hand:** scenario `manager-confirmed`. At 08:10 008–020 are "Tạm hủy". After 08:20 008–014 show "Có mặt" with "QL xác nhận (DMP)"; after 08:25 015–020 too, with "Vào 08:25". On `/today` such an employee reads "Quản lý đã xác nhận bạn có mặt trên DMP."
- The case "confirmation arriving after finalization" needs finalization, which is P4; it moved to P4's exit criteria.

## 6. Presence confirmed by an admin (BR-33, reason optional)

- **Automated:** `AttendanceTest.confirmPresence` (HC admin without a reason, audit with no reason; confirming twice changes nothing; dept admin with a reason; the employee's `/today` shows the confirmation without the reason; the next day answers 409) and `admin-today-screen.test.tsx`.
- **By hand:** on a cancelled row press "Xác nhận có mặt", leave the reason empty and confirm. The row shows "Có mặt" and "{admin} xác nhận lúc …", and the case "Quản trị xác nhận có mặt".

## 7. An API error or under 50% present causes no cancellation and shows a warning

- **Automated:** `AttendanceTest.apiError` (`HELD_SYNC_FAILED`, no cancellation, later polls do not cancel, HC admin banners `ATTENDANCE_SYNC_FAILED` and `ATTENDANCE_CUTOFF_HELD`, none for an employee; the HC admin's re-run against the on-time mock cancels nobody and clears the banners) and `tooLittleData` (`HELD_TOO_FEW_PRESENT 34/74`).
- **By hand:** scenario `api-error`. After 07:00 the HC admin sees a red banner "Lấy dữ liệu chấm công … thất bại…", and after 08:10 a second one saying nothing was cancelled; `/admin/today` shows the error and "không hủy vì chưa có dữ liệu chấm công". Switch to `on-time`, restart, and press "Chạy lại đồng bộ": the banners go away.
- Scenario `too-little-data` with meals for employees beyond `PNJP000510` (more than half of those with meals): at 08:10 the banner says "chỉ x/y người có suất ăn đã chấm công…" and nothing is cancelled.

## 8. Morning leave loses lunch, afternoon leave keeps it

- **Automated:** `AttendanceTest.morningLeave` (195 AM flags for 13 people × 15 days; cancelled at 08:10, still cancelled after the 13:00 check-in; `/admin/today` shows "Nghỉ sáng" and the case "Có đơn nghỉ nhưng vẫn có suất"; 21 meals counted of 34; registrations untouched) and `afternoonLeave` (PM flag, present at 08:10; flags DMP no longer returns are removed).
- **By hand:** scenario `morning-leave`: the leave flag shows on `/admin/today` and `/today` ("Có đơn nghỉ buổi sáng"), and at 08:10 those employees are "Tạm hủy" because they did not check in (BR-35 through BR-30). Scenario `afternoon-leave`: the "Nghỉ chiều" flag shows and the meals stay. Leave syncs at minute 30 of every hour; "Chạy lại đồng bộ" syncs it at once.

## 9. Edits after the lock (BR-24) and scope

- **Automated:** `AttendanceTest.editAfterLock` (409 before 08:00; reason required; an increase is flagged extra with the reason and audited; down to zero; a dish not on the menu is refused; 409 the next day), `scope` and `forbidden` (employees and system admins get 403 on `/admin/today`; a dept admin of another workshop on confirm and edit; only HC and system admins may re-run the sync).
- **By hand:** after 08:00 "Sửa suất" opens the day's dishes with steppers; "Lưu" stays disabled until a reason is typed.

## Real DMP

Not done: it needs the service account (plan section 8). Once it exists, set `DMP_MODE=http`, `DMP_BASE_URL`, `DMP_SERVICE_EMAIL`, `DMP_SERVICE_PASSWORD`, `DMP_COMPANY_CODE`, run "Chạy lại đồng bộ" for a past day and compare `SyncRun.recordCount` with DMP's own count for that day. Check the field names first (Q17, Q32).
