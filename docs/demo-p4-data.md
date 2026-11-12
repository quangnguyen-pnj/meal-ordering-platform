# Data mẫu demo P4: mọi trường hợp

Script `scripts/demo-p4-data.mjs` tạo một tuần (thứ Hai đến thứ Sáu) có đủ các trường hợp của P4. Kịch bản DMP giả `p4-demo` (trong `apps/api/meal-api/src/main/resources/dmp/scenarios.json`) cho các trường hợp chấm công. Tiêu chí nghiệm thu từng mục: [demo-p4.md](demo-p4.md).

## Chạy

1. Trong `.env`: `DMP_MOCK_SCENARIO=p4-demo`, `CLOCK_FAKE_ENABLED=true`.
2. Chạy lần lượt (`db:local` và `dev` giữ terminal):

```bash
pnpm install
```

```bash
pnpm db:local
```

```bash
pnpm db:seed
```

```bash
pnpm dev
```

```bash
node scripts/demo-p4-data.mjs
```

Mặc định là tuần có thứ Hai kế tiếp; muốn bắt đầu từ tuần khác thì truyền ngày thứ Hai, ví dụ `node scripts/demo-p4-data.mjs 2026-11-23`. Tuần nào đã có thực đơn hoặc đăng ký thì script bỏ qua và lấy tuần trống kế tiếp; ngày thứ Hai được dùng in ra ở cuối ("Fake clock: …").

Script để đồng hồ giả ở **thứ Hai 07:20**, dự trù đã chốt. Tài khoản (PIN `123456`): HC `PNJP000003`, admin phòng ban `PNJP000002` (Xưởng Chế tác), system admin `PNJP000004` (đổi giờ giả bằng `PUT /api/dev/clock`, body `{"at":"<ngày>T08:10:00+07:00"}`).

## Data

| Nhóm  | Nhân viên                 | Món                            |
| ----- | ------------------------- | ------------------------------ |
| Món A | `PNJP000001`, `008`–`040` | 34 người; `025` đăng ký 2 suất |
| Món B | `042`–`091`               | 50 người                       |
| Món C | `092`–`096`               | 5 người, không ai thay đổi     |
| Món D | không ai trước dự trù     |                                |

Thay đổi sau dự trù (mọi ngày trong tuần):

| Nhân viên | Thay đổi                       | Trường hợp                         |
| --------- | ------------------------------ | ---------------------------------- |
| `024`     | hủy món A                      | Không ăn (NOT_EATING)              |
| `025`     | 2 suất → 1 suất                | Giảm suất (REDUCED)                |
| `026`     | món A → món B                  | Đổi món (DISH_CHANGED)             |
| `040`     | thêm món B                     | Thêm suất (ADDED)                  |
| `041`     | đăng ký món D, không có dự trù | Thêm suất, món D "Không có dự trù" |
| `097`     | HC thêm món B kèm lý do        | Suất bổ sung (EXTRA)               |

Chấm công (kịch bản `p4-demo`, người khác vào lúc 07:45):

| Nhân viên   | DMP trả về                           | Trường hợp                        |
| ----------- | ------------------------------------ | --------------------------------- |
| `008`–`010` | không chấm công                      | Tạm hủy → Đã hủy do vắng khi chốt |
| `011`–`012` | không chấm công                      | để admin bấm "Xác nhận có mặt"    |
| `013`–`014` | vào 08:15                            | Tạm hủy, không tự khôi phục       |
| `015`–`016` | vào 08:05, DMP nhận lúc 08:20        | Tự khôi phục lúc 08:20            |
| `017`–`018` | quản lý xác nhận, DMP nhận lúc 08:20 | QL xác nhận trên DMP              |
| `019`–`020` | quản lý xác nhận, DMP nhận lúc 08:40 | QL xác nhận sau khi chốt          |
| `021`–`022` | vào 08:05, DMP nhận lúc 08:40        | Chấm công sau khi chốt            |
| `023`       | nghỉ phép buổi sáng, vào 13:00       | Nghỉ phép nhưng vẫn có suất       |

Món A sẽ "Vượt ngưỡng", món B "Lệch" dưới ngưỡng 10%, món C "Khớp", món D "Không có dự trù".

## Thứ Hai: đi qua cả ngày

Sau mỗi lần đổi giờ, đợi tối đa 1 phút cho job, hoặc bấm "Chạy lại đồng bộ" trên `/admin/today`.

| Giờ giả | Làm gì                                                                              | Thấy gì                                                                                        |
| ------- | ----------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| 07:20   | Mở `/admin/reconciliation` (HC)                                                     | Dự trù theo món; tab "Cần xem xét" có 024, 025, 026, 040, 041, 097                             |
| 08:10   | Mở `/admin/today`                                                                   | 008–023 "Tạm hủy" (015–018 sẽ được khôi phục lúc 08:20)                                        |
| 08:15   | Trên `/admin/today`, bấm "Xác nhận có mặt" cho 011 (không lý do) và 012 (có lý do)  | "Quản trị xác nhận có mặt"                                                                     |
| 08:20   | Mở lại `/admin/reconciliation`                                                      | 015–016 tự khôi phục; 017–018 "QL xác nhận trên DMP"; 4 trạng thái món; nút "Chốt số" còn khóa |
| 08:30   | Bấm "Chốt số" **trước 08:40**                                                       | "Đã chốt"; tải file chốt số; `/admin/today` không còn nút sửa                                  |
| 08:45   | Bấm "Chạy lại đồng bộ"                                                              | 019–020 "QL xác nhận sau khi chốt", 021–022 "Chấm công sau khi chốt"; số đã chốt không đổi     |
| 09:00   | Tab "Điều chỉnh": thêm +1 món A cho 019 (tick "Nhà cung cấp đã đồng ý"), −1 cho 040 | "Hiệu lực" thay đổi, "Đã chốt" giữ nguyên; tải file bổ sung                                    |
| 09:00   | Đăng nhập `PNJP000002`                                                              | Chỉ thấy Xưởng Chế tác, không có nút chốt hay tab điều chỉnh                                   |
| 10:30   | Thử thêm điều chỉnh                                                                 | "Đã quá hạn điều chỉnh"                                                                        |

Trường hợp `023` (nghỉ phép sáng) chỉ hiện khi job đồng bộ nghỉ phép đã chạy. Job chạy theo giờ thật, ở phút 30 mỗi giờ.

## Các ngày khác trong tuần

- **Thứ Ba, tự chốt:** đặt giờ giả thứ Ba 08:45 → banner "chưa chốt số". Không bấm chốt, đặt 12:00 → hệ thống tự chốt.
- **Thứ Tư, giữ mốc chấm công:** đổi `DMP_MOCK_SCENARIO=api-error`, restart `pnpm dev`, đặt giờ giả thứ Tư 08:10 → không ai bị tạm hủy, thẻ trạng thái báo "Mốc … đang được giữ", hộp thoại chốt cảnh báo mọi suất đều được tính. Đổi lại `p4-demo` sau khi xong.
- **Thứ Năm, Thứ Sáu:** dự phòng để demo lại từ đầu.

Tải "Dự trù tuần" ở `/admin/registrations` (HC) để xem file dự trù theo tuần.
