import { describe, expect, it } from 'vitest';
import { ACTIONS, PERMISSION_MATRIX } from './permissions';

describe('permission matrix (plan section 3)', () => {
  it('every action has at least one granting role', () => {
    for (const action of ACTIONS) {
      expect(Object.keys(PERMISSION_MATRIX[action]).length, action).toBeGreaterThan(0);
    }
  });

  it('employees act on their own data and see their own meal unit only', () => {
    const employee = ACTIONS.filter((a) => PERMISSION_MATRIX[a].EMPLOYEE).map((a) => [
      a,
      PERMISSION_MATRIX[a].EMPLOYEE,
    ]);
    expect(employee).toEqual([
      ['menu.registerOwn', 'SELF'],
      ['team.view', 'OWN_UNIT'],
    ]);
  });

  it('dept admins have no HC-only action', () => {
    for (const action of ['day.finalize', 'settings.manage', 'menu.manage'] as const) {
      expect(PERMISSION_MATRIX[action].DEPT_ADMIN, action).toBeUndefined();
    }
  });

  it('only the system admin assigns privileged roles and configures integrations', () => {
    expect(PERMISSION_MATRIX['roles.assignPrivileged']).toEqual({ SYS_ADMIN: 'SYSTEM' });
    expect(PERMISSION_MATRIX['integration.configure']).toEqual({ SYS_ADMIN: 'SYSTEM' });
  });
});
