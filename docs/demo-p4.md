# Phase 4 – how to demo each exit criterion

The "Done when" items of plan P4 (v1.4), and exactly how to show each one. Commands run from the repository root. Setup, accounts and the mock DMP scenarios are as in [demo-p3.md](demo-p3.md). P4 adds Flyway migration V7 (`pnpm db:seed` applies it) and the setting `close.autoFinalizeAt` (12:00, product owner 2026-11-10).

```bash
pnpm install
pnpm db:seed
pnpm dev                    # API :3001 (jobs on: poll-attendance and close-day every minute), web :3000
```

Accounts (PIN `123456`): employee `PNJP000001`, dept admin `PNJP000002` (Xưởng Chế tác), HC admin `PNJP000003`, system admin `PNJP000004` (fake time with `PUT /api/dev/clock`, demo-p0 section 3).

Automated proof of everything below:

```bash
pnpm --filter @meal/api test   # DayCloseRulesTest, CountGridTest, DayCasesTest (late cases),
                               # CloseTest (every criterion with fake time, week 2029-04-02),
                               # MigrationTest (V7 tables, write-once triggers), SecretsTest
pnpm --filter @meal/web test   # reconciliation-screen.test.tsx, banners.test.ts
```

## Preparing a day by hand

1. Publish a menu for a week and register meals (demo-p2), so the forecast is taken at closeAt.
2. As `PNJP000004`, set fake time to a serving day before 07:00, then move it forward (07:00, 08:10, 08:30, …) as in demo-p3. Use scenario `forgot-to-check-in` so 008–020 are provisionally cancelled at 08:10.
3. Open `/admin/reconciliation` as `PNJP000003` (whole factory) and as `PNJP000002` (Xưởng Chế tác, read-only).

## 1. File totals equal FinalCount totals and the reconciliation screen

- **Automated:** `CloseTest.finalizeDay` (22 meals: `FinalCount` = `FinalEmployeeLine` = the screen's "Đã chốt") and `CloseTest.files` (the "Tổng cộng" row of "Chốt số 02-04" equals `FinalCount`; the box labels add up to it; the weekly file has one sheet per serving day, each 34).
- **By hand:** at 08:30 press "Chốt số" and confirm. The cards show "Thực tế" and "Đã chốt". Press "Tải file chốt số": the "Tổng cộng" row equals the "Thực tế" card; the "Nhãn hộp cơm" sheet has one label per meal unit. On the "Dự trù tuần" tab, "Tải file dự trù tuần" gives one sheet per day whose totals match the tab.

## 2. A manager's DMP confirmation arriving after finalization adds nothing and only shows as a case to review

- **Automated:** `CloseTest.lateDmpConfirmation` (scenario `manager-confirmed` synced at 08:40: still `ABSENT`, no `presence.restored`, `FinalCount` unchanged, case `LATE_DMP_CONFIRMATION`) and `CloseTest.lateCheckIn` (scenario `late-record`: case `LATE_CHECK_IN`).
- **By hand:** finalize with `forgot-to-check-in`, switch `DMP_MOCK_SCENARIO` to `manager-confirmed`, restart, and press "Chạy lại đồng bộ" before 10:30. On "Cần xem xét", 008–014 show "Đã hủy do vắng" and "QL xác nhận trên DMP sau khi chốt"; the counts do not move. The HC admin decides on an adjustment (section 4). After 10:30 the re-run answers "Ngày này đã chốt số…" and the poll stops.

## 3. Every normal edit API for a finalized day is rejected

- **Automated:** `CloseTest.editsRejected`: `PUT /admin/today/employees/{code}/meals`, `POST /admin/registrations/employees/{code}/extras`, `POST /admin/today/employees/{code}/presence` (HC and dept admin), the bulk grid and the employee's own day all answer 409 (`DAY_FINALIZED` for the admin routes); `/admin/today` shows `window: CLOSED`.
- **By hand:** after finalizing, `/admin/today` says "Đã chốt số lúc …" and has no "Sửa suất" or "Xác nhận có mặt" buttons.

## 4. An HC admin adjustment before 10:30 is accepted, leaves FinalCount unchanged and changes the effective count

- **Automated:** `CloseTest.adjustments` (+1 with the caterer tick and a reason: 201, `FinalCount` still 21, effective 22; −1 without the tick: 201; missing tick, blank reason and going below zero: 400 with the line's code; the supplementary file "Điều chỉnh 1" holds only the changed cell; the employee's `/today` shows final 1, effective 2 and no reason; `/history` shows 2).
- **By hand:** on the "Điều chỉnh" tab press "Thêm điều chỉnh", pick an employee, enter `1` for a dish, type a reason and tick "Nhà cung cấp đã đồng ý". "Lưu điều chỉnh" stays disabled without the tick. The "Đã chốt" card grows by one, "Thực tế" does not; "Tải file bổ sung" gives only the changed cell. As that employee, `/today` shows "Đã điều chỉnh sau chốt: từ 1 thành 2 suất."

## 5. An adjustment after 10:30 or by a dept admin is rejected

- **Automated:** `CloseTest.adjustments` (dept admin: 403; HC admin at 10:30: 409 `ADJUSTMENT_CLOSED`) and `CloseTest.forbidden` (403 on every finalize, file and adjustment route for employees, system admins and dept admins; 404 for an unknown adjustment id).
- **By hand:** as `PNJP000002` the screen has no "Chốt số" button and no "Dự trù tuần" or "Điều chỉnh" tab. As `PNJP000003` after 10:30 the tab says "Đã quá hạn điều chỉnh" and has no add button.

## 6. Files open in Excel with correct Vietnamese text

- **Automated:** `CloseTest.files` reads the sheets back with Apache POI: sheet names "Chốt số 02-04", "Nhãn hộp cơm", "T2 02-04", headers "Tầng", "Đơn vị nhận cơm", dish names.
- **By hand:** open the three files in Excel (or LibreOffice): Vietnamese names, floors and dishes display correctly, counts are numbers (sums work), and the supplementary file shows signed changes.

## Also in P4

- **BR-41 / BR-42:** before finalizing, "Theo món" shows forecast, actual, difference, "Khớp"/"Lệch" and the warning "Lệch x% (ngưỡng 10%)"; "Cần xem xét" lists every case (`CloseTest.reconciliation`).
- **BR-43 timing:** "Chốt số" is disabled before 08:30 (API 409 `TOO_EARLY`); from 08:45 the HC admin sees the banner "Ngày … chưa chốt số"; at 12:00 the `close-day` job finalizes the day by itself (`CloseTest.reminderAndAutoFinalize`). After a held cutoff the confirm dialog warns that every meal will count (`CloseTest.heldCutoff`).
