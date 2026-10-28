package vn.pnj.meal.rules.access;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.pnj.meal.rules.access.Action.DAY_FINALIZE;
import static vn.pnj.meal.rules.access.Action.INTEGRATION_CONFIGURE;
import static vn.pnj.meal.rules.access.Action.MENU_MANAGE;
import static vn.pnj.meal.rules.access.Action.MENU_REGISTER_OWN;
import static vn.pnj.meal.rules.access.Action.PIN_RESET;
import static vn.pnj.meal.rules.access.Action.ROLES_ASSIGN_PRIVILEGED;
import static vn.pnj.meal.rules.access.Action.SETTINGS_MANAGE;
import static vn.pnj.meal.rules.access.Action.TEAM_VIEW;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AccessTest {
  private static final String FACTORY = "f1";
  private static final String PROD = "/ou_1/ou_2/";
  private static final String PROD_LINE = "/ou_1/ou_2/ou_3/";
  private static final String OFFICE = "/ou_1/ou_20/";
  private static final String OFFICE_LIKE = "/ou_1/ou_200/";

  private static final AccessPrincipal EMPLOYEE =
      new AccessPrincipal("e1", Set.of(Role.EMPLOYEE), PROD_LINE, List.of(), List.of());
  private static final AccessPrincipal DEPT_ADMIN =
      new AccessPrincipal(
          "e2", Set.of(Role.EMPLOYEE, Role.DEPT_ADMIN), OFFICE, List.of(PROD), List.of());
  private static final AccessPrincipal HC_ADMIN =
      new AccessPrincipal(
          "e3", Set.of(Role.EMPLOYEE, Role.HC_ADMIN), OFFICE, List.of(), List.of(FACTORY));
  private static final AccessPrincipal SYS_ADMIN =
      new AccessPrincipal("e4", Set.of(Role.EMPLOYEE, Role.SYS_ADMIN), null, List.of(), List.of());

  private static ResourceScope org(String path) {
    return new ResourceScope.Org(FACTORY, path);
  }

  private static final ResourceScope FACTORY_SCOPE = new ResourceScope.Factory(FACTORY);
  private static final ResourceScope SYSTEM = new ResourceScope.SystemWide();

  @Nested
  @DisplayName("permission matrix (plan section 3)")
  class Matrix {
    @Test
    @DisplayName("every action has at least one granting role")
    void everyActionGranted() {
      for (Action action : Action.values()) {
        assertThat(action.grants()).as(action.code()).isNotEmpty();
      }
    }

    @Test
    @DisplayName("action codes are the ones the web app uses")
    void codes() {
      assertThat(Arrays.stream(Action.values()).map(Action::code))
          .contains("menu.registerOwn", "pin.reset", "roles.assignPrivileged")
          .doesNotHaveDuplicates()
          .hasSize(22);
    }

    @Test
    @DisplayName("employee can only act on their own data")
    void employeeSelf() {
      assertThat(Access.authorize(EMPLOYEE, MENU_REGISTER_OWN, new ResourceScope.Self("e1")))
          .isTrue();
      assertThat(Access.authorize(EMPLOYEE, MENU_REGISTER_OWN, new ResourceScope.Self("other")))
          .isFalse();
    }

    @Test
    @DisplayName("employee sees colleagues only in their own meal unit (team.view)")
    void employeeTeam() {
      assertThat(Access.authorize(EMPLOYEE, TEAM_VIEW, org(PROD_LINE))).isTrue();
      assertThat(Access.authorize(EMPLOYEE, TEAM_VIEW, org(OFFICE))).isFalse();
    }

    @Test
    @DisplayName("dept admin acts inside assigned nodes including children")
    void deptInside() {
      assertThat(Access.authorize(DEPT_ADMIN, PIN_RESET, org(PROD))).isTrue();
      assertThat(Access.authorize(DEPT_ADMIN, PIN_RESET, org(PROD_LINE))).isTrue();
    }

    @Test
    @DisplayName("dept admin is denied outside assigned nodes, including prefix look-alikes")
    void deptOutside() {
      assertThat(Access.authorize(DEPT_ADMIN, PIN_RESET, org(OFFICE))).isFalse();
      assertThat(Access.authorize(DEPT_ADMIN, PIN_RESET, org(OFFICE_LIKE))).isFalse();
      assertThat(Access.authorize(DEPT_ADMIN, PIN_RESET, FACTORY_SCOPE)).isFalse();
    }

    @Test
    @DisplayName("dept admin has no HC-only action")
    void deptNoHc() {
      assertThat(Access.authorize(DEPT_ADMIN, DAY_FINALIZE, org(PROD))).isFalse();
      assertThat(Access.authorize(DEPT_ADMIN, SETTINGS_MANAGE, org(PROD))).isFalse();
      assertThat(Access.hasAnyGrant(DEPT_ADMIN, MENU_MANAGE)).isFalse();
    }

    @Test
    @DisplayName("HC admin reaches the whole of their own factory only")
    void hcFactory() {
      assertThat(Access.authorize(HC_ADMIN, PIN_RESET, org(OFFICE))).isTrue();
      assertThat(Access.authorize(HC_ADMIN, DAY_FINALIZE, FACTORY_SCOPE)).isTrue();
      assertThat(
              Access.authorize(HC_ADMIN, PIN_RESET, new ResourceScope.Org("other-factory", OFFICE)))
          .isFalse();
    }

    @Test
    @DisplayName(
        "HC admin cannot assign privileged roles or configure integrations (system admin only)")
    void hcNotSystem() {
      assertThat(Access.authorize(HC_ADMIN, ROLES_ASSIGN_PRIVILEGED, SYSTEM)).isFalse();
      assertThat(Access.authorize(HC_ADMIN, INTEGRATION_CONFIGURE, SYSTEM)).isFalse();
      assertThat(Access.authorize(SYS_ADMIN, ROLES_ASSIGN_PRIVILEGED, SYSTEM)).isTrue();
    }

    @Test
    @DisplayName("system admin resets PINs everywhere but has no meal-operations actions")
    void sysAdmin() {
      assertThat(Access.authorize(SYS_ADMIN, PIN_RESET, org(OFFICE))).isTrue();
      assertThat(Access.authorize(SYS_ADMIN, DAY_FINALIZE, FACTORY_SCOPE)).isFalse();
      assertThat(Access.authorize(SYS_ADMIN, TEAM_VIEW, org(OFFICE))).isFalse();
    }

    @Test
    @DisplayName("roles stack: a dept admin is still an employee for their own meals")
    void rolesStack() {
      assertThat(Access.authorize(DEPT_ADMIN, MENU_REGISTER_OWN, new ResourceScope.Self("e2")))
          .isTrue();
    }
  }

  @Nested
  @DisplayName("list scopes")
  class ListScopes {
    @Test
    @DisplayName("dept admin lists are filtered to assigned paths")
    void dept() {
      ListScope scope = Access.resolveListScope(DEPT_ADMIN, PIN_RESET);
      assertThat(scope).isEqualTo(new ListScope.Paths(List.of(PROD)));
      assertThat(Access.listScopeIncludes(scope, FACTORY, PROD_LINE, null)).isTrue();
      assertThat(Access.listScopeIncludes(scope, FACTORY, OFFICE_LIKE, null)).isFalse();
    }

    @Test
    @DisplayName("HC admin lists cover their factories; system admin lists cover everything")
    void hcAndSys() {
      assertThat(Access.resolveListScope(HC_ADMIN, PIN_RESET))
          .isEqualTo(new ListScope.Factories(List.of(FACTORY)));
      assertThat(Access.resolveListScope(SYS_ADMIN, PIN_RESET)).isEqualTo(new ListScope.All());
    }

    @Test
    @DisplayName("a role with no grant gets an empty list scope")
    void none() {
      assertThat(Access.resolveListScope(EMPLOYEE, PIN_RESET)).isEqualTo(new ListScope.None());
      assertThat(Access.listScopeIncludes(new ListScope.None(), FACTORY, PROD, null)).isFalse();
    }

    @Test
    @DisplayName("employee team list is limited to their own meal unit")
    void team() {
      assertThat(Access.resolveListScope(EMPLOYEE, TEAM_VIEW))
          .isEqualTo(new ListScope.Paths(List.of(PROD_LINE)));
    }
  }
}
