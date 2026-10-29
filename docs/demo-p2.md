# Phase 2 – how to demo each exit criterion

> The "Automated" references name test files of the NestJS API, which was removed in J3; their Java ports are listed in [demo-j3.md](demo-j3.md).

The "Done when" items of plan P2, and exactly how to show each one. Commands run from the repository root. Setup and accounts are as in [demo-p0.md](demo-p0.md) and [demo-p1.md](demo-p1.md). P2 adds no migration.

```bash
pnpm install
pnpm db:seed
pnpm dev                    # API :3001 (jobs on, incl. close-week every minute), web :3000
```

Accounts (PIN `123456`, change at first sign-in): employee `PNJP000001` (team Nhóm Nhẫn 1, meal unit Tổ Nhẫn), dept admin `PNJP000002` (Xưởng Chế tác), HC admin `PNJP000003`, system admin `PNJP000004` (sets fake time).

Automated proof of everything below:

```bash
pnpm --filter @meal/web test           # registration-draft / grid-draft (grid parsing)
pnpm --filter @meal/api test           # RegistrationRulesTest (BR-20 table, BR-22..25, BR-36) and
                                       # RegistrationTest (the walk below, with fake time)
```

## 1. The walk with fake time

The plan's criterion: register on Tuesday, free edits, forecast snapshot at Thursday 23:59, then one employee adds a meal and another with no forecast registers for Monday at 07:59, full lock at 08:00 on day D, and both show as differences from the forecast.

`apps/api/meal-api/src/test/java/vn/pnj/meal/api/registrations/RegistrationTest.java` (orders 20–35) does exactly this for week N = 2027-10-04 (closeAt Friday 2027-10-01 00:00, shown as Thursday 23:59):

| Fake time (Vietnam) | Step                                                                     | Expected                                                               |
| ------------------- | ------------------------------------------------------------------------ | ---------------------------------------------------------------------- |
| Tue 28/09 10:00     | Employee A saves Mon–Wed                                                 | 200, every day `OPEN` (BR-22)                                          |
| Wed 29/09 15:00     | A changes Tue and Wed                                                    | accepted, not audited as "after close"                                 |
| Thu 30/09 23:59:30  | A changes Thursday                                                       | accepted, still `OPEN` (BR-03)                                         |
| Fri 01/10 00:00     | `close-week` job                                                         | week `CLOSED`, A's lines snapshotted with mapping (BR-07, BR-64), once |
| Sat 02/10 10:00     | A adds a Chay to Monday                                                  | `AFTER_CLOSE`, forecast 1 / actual 2, audited (BR-23)                  |
| Mon 04/10 07:59     | Colleague B, with no forecast, registers Monday                          | accepted, forecast 0 / actual 1 (BR-23)                                |
| Mon 04/10 08:00     | A and B try to change Monday; the dept admin tries to register on behalf | `409 DAY_LOCKED` (BR-24, BR-21); Tuesday stays editable                |
| Mon 04/10 08:00     | A's day view, B's day view, the dept admin's grid                        | both show `changed`, forecast vs actual                                |
| Mon 04/10 08:05     | Dept admin adds an Extra Cháo for A, with and without a reason           | 400 without reason, 200 with it, flagged "Extra", audited (BR-25)      |
| end                 | Snapshot compared with the one taken at closeAt                          | identical; `DELETE` on a forecast line is rejected (rule 7)            |

The same file also covers a missed job: the first request after week N+1's closeAt takes the snapshot before the change (self-healing, plan 6.5). It also covers BR-14 cancellation at the lock, BR-27, BR-36 on sync and the 403s.

### By hand

1. Sign in as `PNJP000004` and set fake time to a Tuesday a week before the target week. Use the `/api/dev/clock` PUT from demo-p0 section 3, for example `{"at":"2026-10-27T10:00:00+07:00"}`, so the target week starts Monday 2026-11-02.
2. As `PNJP000003`, build and publish next week's menu (demo-p1 section 2).
3. As `PNJP000001`, open **Đăng ký tuần** (`/week`). The coming week is preselected. The yellow banner says "Đăng ký và sửa tự do đến hết 29/10/2026 23:59…". Use the + / − steppers (no cap: 3 of one dish is fine), then **Lưu đăng ký**. The message confirms the week's meal count and the deadline.
4. Set the clock to Thursday 23:59 and change a day: accepted. Set it to Friday 00:01. Within a minute the `close-week` job takes the snapshot, or the next page load does. The banner turns grey: "Đã chốt dự trù tuần…". Each day shows "Còn sửa được đến 08:00 …", and a day you change shows "Dự trù: n suất" and the badge **Khác dự trù**.
5. As a colleague who registered nothing (any other employee of Nhóm Nhẫn 1), set the clock to Monday 07:59 and register on **Hôm nay** (`/today`). The page counts down to 08:00. At 08:00 the steppers disappear and the page says to contact the dept admin.

## 2. The bulk grid saves 50 employees × 5 days

Automated: `registration.e2e-spec.ts` → "P2 exit criterion: the bulk grid saves 50 employees × 5 days". It saves five day batches of 50 rows, checks every cell and the row count, and checks one audit entry per changed employee. It also shows that one bad row (negative, fractional or outside the unit) rejects the whole batch with row-level errors and writes nothing. It takes a few seconds.

By hand, on a 768 px wide window (a tablet):

1. Sign in as `PNJP000002`, open **Đăng ký tuần (quản trị)** → **Nhập theo bảng**.
2. Pick "… / Tổ Nhẫn / Nhóm Nhẫn 1". Rows are employees, columns are that day's meals, and a tab per day. Type counts; **Enter** moves down the column, **Tab** moves right. A wrong value (`x`, `1,5`) turns the row red with "Số suất phải là số nguyên từ 0" and disables saving.
3. **Lưu ngày Thứ Hai** writes the whole day: "Đã lưu: n người thay đổi (… suất ở các dòng vừa lưu).". Repeat for each day tab.
4. **Tiến độ** shows Tổ Nhẫn's registered count going up. **Chưa đăng ký** lists who still has no meal in the week, or on one day.
5. **In phiếu đăng ký** (`/admin/print`): pick the same unit and press **In phiếu**. Each day prints on its own page, in the grid's row order, with blank cells and a signature line (J2).

After the lock (Monday 08:00) the Monday tab is read-only, and each row offers **Thêm suất bổ sung** (dish, quantity, required reason; BR-25).

## 3. The forecast snapshot is unchanged after edits

Automated: the last step of the walk compares every `ForecastLine` of week N with the snapshot taken at closeAt. This runs after the BR-23 changes, the extra, a BR-14 dish removal, the BR-14 cancellation at the lock and a BR-36 leaver cancellation. The database trigger from P1 rejects any `UPDATE` or `DELETE`.

By hand: count the lines before and after editing.

```sql
SELECT "employeeId", "date", "menuItemId", "quantity", "mealUnitId", "floorId", "costCenterId"
FROM "ForecastLine" fl JOIN "MenuWeek" w ON w.id = fl."menuWeekId"
WHERE w."weekStart" = '2026-11-02' ORDER BY 1, 2, 3;
```

## Other P2 behaviour

- **BR-26 shortcuts:** removed on 2026-10-23 at the product owner's request (see `decisions.md`).
- **BR-27:** **Đơn vị của tôi** (`/team`) shows colleagues of your meal unit today: name, meals, and "Có ăn" / "Không ăn". It never shows a reason, and you can search by name or code.
- **History:** **Lịch sử** (`/history`) shows past days with the forecast, the actual meals (cancelled ones noted) and a status, 4 weeks at a time.
- **BR-14 at the lock:** when the HC admin removes a dish from a published menu, the employee sees a notice and a warning on the day. If they have not reselected by 08:00 that day, the `reselect-deadline` job cancels it, with an audit entry.
- **BR-36:** when the daily sync sees an `endDate`, meals on and after that date are cancelled. Days whose lock has passed are not touched. The count is in the sync run details.
