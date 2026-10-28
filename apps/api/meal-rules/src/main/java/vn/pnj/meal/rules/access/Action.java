package vn.pnj.meal.rules.access;

import static vn.pnj.meal.rules.access.Role.DEPT_ADMIN;
import static vn.pnj.meal.rules.access.Role.EMPLOYEE;
import static vn.pnj.meal.rules.access.Role.HC_ADMIN;
import static vn.pnj.meal.rules.access.Role.SYS_ADMIN;
import static vn.pnj.meal.rules.access.ScopeLevel.FACTORY;
import static vn.pnj.meal.rules.access.ScopeLevel.OWN_UNIT;
import static vn.pnj.meal.rules.access.ScopeLevel.SELF;
import static vn.pnj.meal.rules.access.ScopeLevel.SYSTEM;
import static vn.pnj.meal.rules.access.ScopeLevel.UNITS;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Permission matrix from plan section 3, one constant per function row, with the scope each role
 * gets. Checked as a (role, action, scope) triple by {@link Access}; the web only hides buttons.
 * {@link #code()} is the name the web app uses ({@code PERMISSION_MATRIX} in packages/shared).
 */
public enum Action {
  MENU_REGISTER_OWN("menu.registerOwn", allSelf()),
  TEAM_VIEW("team.view", grants(EMPLOYEE, OWN_UNIT, DEPT_ADMIN, UNITS, HC_ADMIN, FACTORY)),
  REGISTRATION_ON_BEHALF("registration.onBehalf", unitsOrFactory()),
  REGISTRATION_ADD_EXTRA("registration.addExtra", unitsOrFactory()),
  PRESENCE_CONFIRM("presence.confirm", unitsOrFactory()),
  REGISTRATION_NOT_REGISTERED_LIST("registration.notRegisteredList", unitsOrFactory()),
  DISHES_MANAGE("dishes.manage", hcOnly()),
  MENU_MANAGE("menu.manage", hcOnly()),
  RECONCILIATION_VIEW("reconciliation.view", unitsOrFactory()),
  DAY_FINALIZE("day.finalize", hcOnly()),
  EXPORT_FINAL_AND_FORECAST("export.finalAndForecast", hcOnly()),
  ADJUSTMENT_MANAGE("adjustment.manage", hcOnly()),
  DELIVERY_CONFIRM_UNIT("delivery.confirmUnit", unitsOrFactory()),
  DELIVERY_CONFIRM_FINAL("delivery.confirmFinal", hcOnly()),
  REPORTS_VIEW("reports.view", unitsOrFactory()),
  PIN_RESET("pin.reset", grants(DEPT_ADMIN, UNITS, HC_ADMIN, FACTORY, SYS_ADMIN, SYSTEM)),
  ORG_MANAGE("org.manage", hcOrSystem()),
  SETTINGS_MANAGE("settings.manage", hcOrSystem()),
  SYNC_MANAGE("sync.manage", hcOrSystem()),
  AUDIT_VIEW("audit.view", hcOrSystem()),
  ROLES_ASSIGN_PRIVILEGED("roles.assignPrivileged", grants(SYS_ADMIN, SYSTEM)),
  INTEGRATION_CONFIGURE("integration.configure", grants(SYS_ADMIN, SYSTEM));

  private final String code;
  private final Map<Role, ScopeLevel> grants;

  Action(String code, Map<Role, ScopeLevel> grants) {
    this.code = code;
    this.grants = Collections.unmodifiableMap(grants);
  }

  public String code() {
    return code;
  }

  /** The scope each role is granted for this action; roles without a grant are absent. */
  public Map<Role, ScopeLevel> grants() {
    return grants;
  }

  private static Map<Role, ScopeLevel> allSelf() {
    return grants(EMPLOYEE, SELF, DEPT_ADMIN, SELF, HC_ADMIN, SELF, SYS_ADMIN, SELF);
  }

  private static Map<Role, ScopeLevel> unitsOrFactory() {
    return grants(DEPT_ADMIN, UNITS, HC_ADMIN, FACTORY);
  }

  private static Map<Role, ScopeLevel> hcOnly() {
    return grants(HC_ADMIN, FACTORY);
  }

  private static Map<Role, ScopeLevel> hcOrSystem() {
    return grants(HC_ADMIN, FACTORY, SYS_ADMIN, SYSTEM);
  }

  private static Map<Role, ScopeLevel> grants(Object... pairs) {
    Map<Role, ScopeLevel> map = new EnumMap<>(Role.class);
    for (int i = 0; i < pairs.length; i += 2) {
      map.put((Role) pairs[i], (ScopeLevel) pairs[i + 1]);
    }
    return map;
  }
}
