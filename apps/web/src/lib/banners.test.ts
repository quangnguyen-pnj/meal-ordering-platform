import { describe, expect, it } from 'vitest';
import { toBanners } from './banners';

describe('banners', () => {
  it('BR-06: the "menu not published" warning names the canteen and week in Vietnamese', () => {
    const [b] = toBanners([
      {
        id: 'menu-not-published:ct_gv:2027-03-01',
        severity: 'warning',
        kind: 'MENU_NOT_PUBLISHED',
        params: { canteen: 'Nhà ăn Gò Vấp', weekStart: '2027-03-01' },
      },
    ]);
    expect(b).toEqual({
      id: 'menu-not-published:ct_gv:2027-03-01',
      severity: 'warning',
      message:
        'Thực đơn tuần bắt đầu 01/03/2027 của Nhà ăn Gò Vấp chưa được công bố. Nhân viên chưa thể đăng ký.',
      action: { href: '/admin/menu', label: 'Mở Thực đơn tuần' },
    });
  });

  it('BR-14: notices carry their id so the employee can dismiss them', () => {
    const [b] = toBanners([
      {
        id: 'notification:n1',
        severity: 'info',
        kind: 'NOTIFICATION',
        notificationId: 'n1',
        notificationType: 'MENU_DISH_REPLACED',
        params: { date: '2027-03-01', from: '[Mặn] Cá kho tộ', to: '[Mặn] Sườn ram' },
      },
    ]);
    expect(b!.dismissId).toBe('n1');
    // A replacement needs nothing from the employee, so no action.
    expect(b!.action).toBeUndefined();
    expect(b!.message).toBe(
      'Thực đơn ngày 01/03/2027: món [Mặn] Cá kho tộ được đổi thành [Mặn] Sườn ram. Suất bạn đã đăng ký đã chuyển sang món mới.',
    );
  });

  it('BR-14: a removed-dish notice names the lock time from the payload, never a fixed hour', () => {
    const notice = (params: Record<string, string>) =>
      toBanners([
        {
          id: 'notification:n2',
          severity: 'info',
          kind: 'NOTIFICATION',
          notificationId: 'n2',
          notificationType: 'MENU_DISH_REMOVED',
          params,
        },
      ])[0]!.message;
    expect(notice({ date: '2027-03-01', dish: 'Mặn Cá kho tộ', lockTime: '07:30' })).toBe(
      'Thực đơn ngày 01/03/2027: món Mặn Cá kho tộ đã bị bỏ. Vui lòng chọn lại món trước 07:30 ngày đó.',
    );
    expect(notice({ date: '2027-03-01', dish: 'Mặn Cá kho tộ' })).toContain(
      'trước giờ khóa ngày đó',
    );
  });

  it('BR-14: a removed-dish notice links to the week screen to reselect', () => {
    const [b] = toBanners([
      {
        id: 'notification:n3',
        severity: 'info',
        kind: 'NOTIFICATION',
        notificationId: 'n3',
        notificationType: 'MENU_DISH_REMOVED',
        params: { date: '2027-03-01', dish: 'Mặn Cá kho tộ', lockTime: '08:00' },
      },
    ]);
    expect(b!.action).toEqual({ href: '/week', label: 'Chọn lại món' });
  });

  it('BR-34: a failed attendance sync and a held cutoff point the HC admin to /admin/today', () => {
    const [failed, held] = toBanners([
      {
        id: 'attendance-sync-failed:2027-03-01',
        severity: 'error',
        kind: 'ATTENDANCE_SYNC_FAILED',
        params: { date: '2027-03-01', at: '2027-03-01T01:05:00Z' },
      },
      {
        id: 'attendance-cutoff-held:2027-03-01',
        severity: 'warning',
        kind: 'ATTENDANCE_CUTOFF_HELD',
        params: {
          date: '2027-03-01',
          outcome: 'HELD_TOO_FEW_PRESENT',
          withMeals: 74,
          presentWithMeals: 34,
        },
      },
    ]);
    expect(failed!.message).toBe(
      'Lấy dữ liệu chấm công ngày 01/03/2027 từ DMP thất bại lúc 08:05. Hệ thống chưa hủy suất ăn nào; hãy chạy lại đồng bộ.',
    );
    expect(failed!.action).toEqual({ href: '/admin/today', label: 'Mở Hôm nay (quản trị)' });
    expect(held!.message).toContain('chỉ 34/74 người có suất đã chấm công');
  });
});
