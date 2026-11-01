import type { Role } from '@meal/shared';
import { describe, expect, it } from 'vitest';
import { NAV_ITEMS, findNavItem, visibleNav } from './nav';

const hrefs = (roles: Role[], group: 'mine' | 'admin') =>
  visibleNav(roles)[group].map((i) => i.href);

describe('role-based navigation (plan section 3 matrix)', () => {
  it('an employee only gets the "Mine" group', () => {
    expect(hrefs(['EMPLOYEE'], 'mine')).toEqual(['/today', '/week', '/team', '/history']);
    expect(hrefs(['EMPLOYEE'], 'admin')).toEqual([]);
  });

  it('a dept admin sees operational admin screens but not HC-only ones', () => {
    const admin = hrefs(['EMPLOYEE', 'DEPT_ADMIN'], 'admin');
    expect(admin).toEqual(
      expect.arrayContaining([
        '/admin/today',
        '/admin/registrations',
        '/admin/print',
        '/admin/reconciliation',
        '/admin/delivery',
        '/admin/reports',
        '/admin/employees',
      ]),
    );
    for (const hcOnly of [
      '/admin/dishes',
      '/admin/menu',
      '/admin/settings',
      '/admin/audit',
      '/admin/org',
      '/system',
    ]) {
      expect(admin).not.toContain(hcOnly);
    }
  });

  it('an HC admin sees every admin screen except system-only ones', () => {
    const admin = hrefs(['EMPLOYEE', 'HC_ADMIN'], 'admin');
    expect(admin).toContain('/admin/menu');
    expect(admin).toContain('/admin/audit');
    expect(admin).not.toContain('/system');
  });

  it('a system admin sees settings, org mapping, audit and /system, but no meal operations', () => {
    const admin = hrefs(['EMPLOYEE', 'SYS_ADMIN'], 'admin');
    expect(admin).toEqual(
      expect.arrayContaining([
        '/admin/settings',
        '/admin/org',
        '/admin/audit',
        '/admin/employees',
        '/system',
      ]),
    );
    expect(admin).not.toContain('/admin/menu');
    expect(admin).not.toContain('/admin/reconciliation');
  });

  it('roles stack: a dept admin who is also HC admin gets the union', () => {
    const admin = hrefs(['EMPLOYEE', 'DEPT_ADMIN', 'HC_ADMIN'], 'admin');
    expect(admin).toContain('/admin/menu');
    expect(admin).toContain('/admin/delivery');
  });

  it('has unique routes and finds nested paths', () => {
    expect(new Set(NAV_ITEMS.map((i) => i.href)).size).toBe(NAV_ITEMS.length);
    expect(findNavItem('/admin/menu/draft')?.href).toBe('/admin/menu');
    expect(findNavItem('/nope')).toBeUndefined();
  });

  it('marks only screens that exist as available; the rest show "Sắp có"', () => {
    const available = NAV_ITEMS.filter((i) => i.available).map((i) => i.href);
    expect(available).toEqual([
      '/today',
      '/week',
      '/team',
      '/history',
      '/admin/today',
      '/admin/registrations',
      '/admin/print',
      '/admin/dishes',
      '/admin/menu',
      '/admin/master',
      '/admin/org',
      '/admin/employees',
      '/admin/settings',
      '/system',
    ]);
  });
});
