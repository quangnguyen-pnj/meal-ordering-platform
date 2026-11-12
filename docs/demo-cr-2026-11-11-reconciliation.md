# Demo: reconciliation layout (CR 2026-11-11)

Setup: `pnpm db:local`, `pnpm db:seed`, `pnpm dev`. As the system admin (`PNJP000004`) set fake time to a serving day (`PUT /api/dev/clock`, demo-p0 section 3) and move it as in demo-p4 (scenario `forgot-to-check-in`). Then sign in as the HC admin (`PNJP000003`, PIN `123456`) and open `/admin/reconciliation`.

## 1. The day's status comes first

1. Under the title (one-line intro), the day picker and the "Khối" filter sit on the title row.
2. The first card says "Chưa chốt" with "Chốt số từ … Nếu chưa chốt, hệ thống tự chốt lúc …" and the "Chốt số" button on the right. Below: the milestones (chốt chấm công, mở chốt số, tự chốt, hạn điều chỉnh), ticked as the clock passes them, then the "Chấm công" line with "Chạy lại đồng bộ".
3. On another serving day, use the DMP mock scenario `api-error` (demo-p3) before the cutoff time: the card shows the amber "Mốc … đang được giữ…" text above the button, the cutoff milestone gets a warning mark, and the sync line turns red.
4. Finalize: the badge becomes "Đã chốt", the line says who and when, "Điều chỉnh được đến …" appears, and "Tải file chốt số" is the main (filled) button.
5. Move the clock past `close.adjustmentDeadline`: "Đã quá hạn điều chỉnh (…)" and no "Chạy lại đồng bộ" button.

## 2. Totals

1. Before finalizing: three cards. "Thực tế" says "−N so với dự trù (−x,y%)"; "Cần xem xét" shows the people and "N món vượt ngưỡng", and clicking it opens the cases tab.
2. After finalizing: Dự trù, Thực tế (the final count, with its difference), Điều chỉnh, Đã chốt (Thực tế + điều chỉnh, highlighted), Cần xem xét.

## 3. Meals by canteen

1. Tab "Theo món": one line above the table states the threshold ("lệch quá 10%").
2. Each canteen is a bold row with its subtotals, its meals indented below; a "Tổng" row closes the table when there are several canteens.
3. Each meal has one badge: "Khớp", "Lệch", "Vượt ngưỡng −10,6%" or "Không có dự trù". Negative numbers use "−", decimals a comma.
4. After finalizing, the second column stays "Thực tế", then Chênh lệch, Điều chỉnh (a dash when zero), Đã chốt, and the status last.

## 4. Cases to review

1. Tab "Cần xem xét": the hint says cases are handled on the Hôm nay page.
2. Rows with red cases (tạm hủy do chưa chấm công, chấm công / QL xác nhận sau khi chốt) come first; presence confirmations and "Đã hủy do vắng" are grey.
3. Click a chip such as "Đổi món (2)": only those rows remain; "Tất cả" brings them back.
4. Each row shows the name, then the code and full unit path, and "dự trù → thực tế" in one cell. The icon at the end opens `/admin/today?date=…&q=CODE` (tooltip "Mở ở trang Hôm nay").

## 5. Adjustments

1. Tab "Điều chỉnh" (HC, after finalizing): "Điều chỉnh được đến …" and "Thêm điều chỉnh" above a table of adjustments, each with its file button.
2. In the dialog, pick an employee: each meal has a − / + stepper starting at the current count. Lowering one shows "Hiện có 1 → 0 (−1)" and no caterer tick; raising one shows the tick "Nhà cung cấp đã đồng ý", required to save.
3. The reason is a multi-line field. Save: the toast confirms, the table and the totals update.

Automated: `apps/web/src/components/screens/reconciliation-screen.test.tsx`.

## Security check

Only the web changed: no new endpoint, request or response. The links to `/admin/today` still carry only a day and an employee code. No PIN or secret is shown, logged or put in a URL (`docs/security.md`, P4 items unchanged).
