# Phase 1 – how to demo each exit criterion

> The "Automated" references name test files of the NestJS API, which was removed in J3; their Java ports are listed in [demo-j2.md](demo-j2.md).

"Done when" from the plan (P1), with the exact way to show each one. Commands run from the repository root. Setup and accounts are the same as [demo-p0.md](demo-p0.md):

```bash
pnpm install
pnpm db:seed                # Flyway applies any new migration first
pnpm dev                    # API :3001 (jobs on), web :3000
```

Accounts: HC admin `PNJP000003`, employee `PNJP000001` (meal unit Tổ Nhẫn, canteen Nhà ăn Gò Vấp), unassigned employee `PNJP000006`, dept admin `PNJP000002`, system admin `PNJP000004`. All start with PIN `123456`.

## 1. closeAt has tests for publishing on Monday, Thursday 23:59, Friday 00:01, Saturday and Sunday 20:00

```bash
pnpm --filter @meal/shared test      # src/rules/close-at.test.ts
```

Week N starts Monday 2026-11-02; the regular closeAt is Friday 2026-10-30 00:00 (shown as 23:59 Thursday).

| Published (Vietnam time) | closeAt              | Rule                                       |
| ------------------------ | -------------------- | ------------------------------------------ |
| Monday 26/10 10:00       | Friday 30/10 00:00   | BR-03                                      |
| Thursday 29/10 23:59     | Friday 30/10 00:00   | BR-03, a late publish no extension         |
| Friday 30/10 00:01       | Saturday 31/10 00:01 | BR-04, + 24 h                              |
| Saturday 31/10 09:30     | Sunday 01/11 09:30   | BR-04                                      |
| Sunday 01/11 20:00       | Monday 02/11 20:00   | BR-04; Monday still locks at 08:00 (BR-05) |

The same file covers BR-05 (`dayFreeEditDeadline`) and BR-06 (the warning starts Monday 12:00). The API repeats a regular and a late publish end to end in `apps/api/test/menu.e2e-spec.ts`.

## 2. The HC admin can build and publish a week

1. Sign in as `PNJP000003`. The yellow banner says next week's menu is not published (BR-06) whenever it is past Monday 12:00.
2. **Thư viện món** (`/admin/dishes`): add a few main dishes (one Chay, one Cháo, two or three Mặn) and a dessert. The display name follows BR-12: the type in brackets, then the name ("[Chay] Gạo lứt muối mè"). Archive one: it leaves the list and the menu library but is kept.
3. **Thực đơn tuần** (`/admin/menu`): pick the canteen; the coming week is preselected and shows "Nếu công bố bây giờ, hạn đăng ký là đến hết …".
   - Drag dishes from the library onto a day; drop one on a dish already there to replace it. Below 1024 px the library is hidden: press "Thêm món" on a day and pick from the list. Add the dessert too.
   - A day with an unusual mix shows a yellow note (BR-13); it never blocks.
   - "Bật phục vụ" on Saturday turns it on for this week; "Tắt (nghỉ lễ)" marks a holiday (BR-08).
4. **Lưu bản nháp**, then **Công bố**. The badge turns "Đã công bố" and the deadline is fixed.
5. Edit after publishing (BR-14): drag a dish from the library onto the dish to replace (below 1024 px: the "Đổi thành" select under it), or remove one, optionally type a reason, then **Lưu thay đổi**. The message counts moved and flagged registrations and notified employees. Real registrations arrive in P2; the e2e test inserts some to prove the effect. Days whose 08:00 has passed are locked.

Automated: `apps/api/test/menu.e2e-spec.ts` ("building and publishing a week", "BR-14", "BR-04: late publish").

Late publish by hand (fake time, system admin session, see demo-p0 section 3): set the clock to a Saturday after the regular close, publish next week's menu, and the deadline is publish + 24 h.

## 3. Employees see their canteen's published menu and never a draft

1. While the week is a draft, sign in as `PNJP000001` and open **Đăng ký tuần** (`/week`): "Thực đơn tuần này của Nhà ăn Gò Vấp chưa được công bố."
2. After publishing, the same page shows the serving days, meals and desserts, and "Hạn đăng ký: đến hết …".
3. `PNJP000006` (no meal unit, BR-60) sees "Bạn chưa thuộc đầu mối nhận cơm nào…" instead of a menu.

Automated: `menu.e2e-spec.ts` "employees see their canteen's published menu". It also maps Phòng IT to a second canteen and shows each employee only their own canteen's menu (BR-15).

## 4. A mapping import with one bad row is rejected with row-level errors and applies nothing

1. **Đơn vị nhận cơm** (`/admin/org`) → tab **Nhập / xuất Excel** → **Tải xuống**. You get `mapping-to-chuc.xlsx`, which is the import template filled with today's mapping. The second sheet lists valid codes.
2. In Excel, change one floor code to `T2` (valid) and another to `T9` (does not exist). Save.
3. **Chọn tệp .xlsx** → **Kiểm tra tệp**. Result: "Tệp bị từ chối: 1 lỗi. Không có thay đổi nào được áp dụng." and a table with the row number, column, value and reason. The tree is unchanged.
4. Fix the bad row and upload again: a preview lists each change (before → after) and nothing is applied until **Áp dụng tất cả**. Every changed node is audited with the file name and row.

Automated: `apps/api/test/org.e2e-spec.ts` "BR-64 exit criterion: one bad row…", which compares the whole mapping and the audit count before and after. The row-level rules are in `packages/shared/src/rules/org-import.test.ts`.

## 5. Changing a mapping leaves existing snapshot rows unchanged

Forecast lines (written by the close-week job from P2) store the meal unit, floor, canteen and cost center in effect when they were taken (BR-64).

```bash
pnpm --filter @meal/api test         # org.e2e-spec.ts "changing a mapping leaves existing snapshot rows unchanged"
```

The test writes a forecast line, then changes the mapping three ways (the org screen, an Excel import and an employee cost-center override) and checks that the line is byte-for-byte the same. It also shows the database rejecting `UPDATE` and `DELETE` on `ForecastLine` (write-once trigger, rule 7). By hand:

```bash
psql "$DATABASE_URL" -c 'UPDATE "ForecastLine" SET quantity = 9'
# ERROR:  ForecastLine rows are write-once (plan 7.1 rule 7)
```

## Other P1 features

- **Org sync** (`/admin/org` → **Đồng bộ từ DMP**, or the job at 02:00): with `DMP_MODE=mock` it finds nothing to change on the seed and writes a `SyncRun`, visible under **Hệ thống** (`/system`) for the system admin.
- **Meal-unit flags and mapping** (`/admin/org`; the UI calls a meal unit "Đầu mối nhận cơm"):
  - Select a node and answer "Đơn vị này có là đầu mối nhận cơm không?". The "Hiện trạng" line updates at once. For example, Tổ Nhẫn reads "Đầu mối của 296 nhân viên, nhận cơm tại Tầng 2 · Nhà ăn Gò Vấp".
  - Choose a floor, canteen or cost center. "Kế thừa: … (từ …)" shows the inherited value and its source, and the hint under floor/canteen names the meal units a value reaches.
  - In the tree, nodes show "→ <đầu mối>" or a "Chưa có đầu mối" warning. "Chỉ xem đầu mối nhận cơm" lists all 13 meal units; Tổ Sửa chữa shows "Thiếu tầng".
  - The tabs **Chưa đăng ký cơm được** and **Thiếu trung tâm chi phí** are the BR-60 and BR-63 lists.
  - Dept admins are assigned and revoked in the same panel. Tổ Nhẫn shows Trần Thị Bình under "Quản lý từ cấp trên (từ Xưởng Chế tác)", since an assignment covers every unit below it.
- **Canteens, floors, cost centers** (`/admin/master`): add, edit, deactivate. Deactivating a floor that is still mapped (e.g. Tầng 1) is refused.
- **Employees** (`/admin/employees`): filter by floor, canteen, cost center or meal status; set a cost-center override (HC and system admins); reset a PIN, which shows the temporary PIN once. A dept admin only sees employees in their units.
- **Settings** (`/admin/settings`): every plan 6.4 key plus the caterer and unit price. Invalid values are refused, and every change is audited.
- **403s**: every new admin endpoint is swept with employee and dept-admin sessions (plus system admin where the matrix excludes them) in `menu.e2e-spec.ts`, `org.e2e-spec.ts` and `admin.e2e-spec.ts`.

## UI review changes (after P1)

See `docs/decisions.md`, "P1 UI review". To see them:

- Menu: placeholder screens are greyed out with "Sắp có"; clicking a real screen shows a spinner and a skeleton. On a phone (390 px) the menu opens over the page and holds the account block.
- `/admin/employees` as `PNJP000003`: "Cấp lại PIN" on any row asks for confirmation, then shows the PIN in a dialog in the middle of the screen. "Đổi TT chi phí" also opens a dialog. On a phone each employee is a card.
- `/admin/settings`: change "Giờ khóa thay đổi trong ngày" to `0730`; it becomes `07:30`, the row is highlighted and a bar offers "Lưu thay đổi (1)" or "Hủy thay đổi".
- `/admin/org` → select Phòng IT → change the floor: "Đã lưu: Tầng." with "Hoàn tác". Under "Quản trị bộ phận", type part of a name and pick the person.
- `/system` as `PNJP000004`: grant a role by searching for the employee; revoking asks for confirmation.
- `/admin/menu`: "Công bố" stays disabled with "Lưu bản nháp trước khi công bố." until the draft is saved; publishing asks for confirmation and repeats the deadline.

## Checks run for this phase

```bash
pnpm lint && pnpm typecheck && pnpm test && pnpm build
pnpm db:migrate && pnpm db:seed
```

The API tests ran against a PostgreSQL 16 via `TEST_DATABASE_URL`, because the embedded PostgreSQL binary needs ICU 60, which the build container lacked. A browser smoke run (Playwright) walked the HC admin through dishes, drag-and-drop menu building, publishing, org, employees, master data and settings, then the employee `/week` page and the system admin `/system` page.
