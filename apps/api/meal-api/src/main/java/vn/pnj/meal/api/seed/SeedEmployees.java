package vn.pnj.meal.api.seed;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 1,700 fake employees, identical to the legacy seed: the 7 demo accounts first, then the rest
 * spread over the org tree by headcount weight. The PRNG and the allocation are ported bit for bit,
 * so names, emails and start dates match the legacy data. The Nhập hàng team carries a DMP-supplied
 * cost center, to exercise the DMP default (BR-62).
 */
public final class SeedEmployees {
  private SeedEmployees() {}

  public static final int COUNT = 1700;

  /** Fixed accounts used by docs, demos and tests (all four roles, plus awkward cases). */
  public static final class Demo {
    private Demo() {}

    public static final String EMPLOYEE = "PNJP000001";
    public static final String DEPT_ADMIN_PRODUCTION = "PNJP000002";
    public static final String HC_ADMIN = "PNJP000003";
    public static final String SYS_ADMIN = "PNJP000004";
    public static final String DEPT_ADMIN_JEWELRY = "PNJP000005";

    /** Under KHO: no meal-unit flag above, so unassigned (BR-60). */
    public static final String UNASSIGNED = "PNJP000006";

    /** Under XSC: the meal unit has no floor, so it is invalid (BR-63). */
    public static final String INVALID_MEAL_UNIT = "PNJP000007";

    public static final List<String> ALL =
        List.of(
            EMPLOYEE,
            DEPT_ADMIN_PRODUCTION,
            HC_ADMIN,
            SYS_ADMIN,
            DEPT_ADMIN_JEWELRY,
            UNASSIGNED,
            INVALID_MEAL_UNIT);
  }

  private record DemoAccount(String code, String name, String email, String node) {}

  private static final List<DemoAccount> DEMO_ACCOUNTS =
      List.of(
          new DemoAccount(Demo.EMPLOYEE, "Nguyễn Văn An", "an.nguyen@pnjp.test", "XCT-A1-1"),
          new DemoAccount(
              Demo.DEPT_ADMIN_PRODUCTION, "Trần Thị Bình", "binh.tran@pnjp.test", "XCT-A"),
          new DemoAccount(Demo.HC_ADMIN, "Lê Hoàng Cường", "cuong.le@pnjp.test", "NS-HC"),
          new DemoAccount(Demo.SYS_ADMIN, "Phạm Minh Dũng", "dung.pham@pnjp.test", "IT"),
          new DemoAccount(Demo.DEPT_ADMIN_JEWELRY, "Võ Thị Em", "em.vo@pnjp.test", "XKH-DA-1"),
          new DemoAccount(Demo.UNASSIGNED, "Đặng Quốc Phong", "phong.dang@pnjp.test", "KHO-XH"),
          new DemoAccount(Demo.INVALID_MEAL_UNIT, "Bùi Thị Giang", "giang.bui@pnjp.test", "XSC-1"));

  /**
   * @param orgUnitCode org node code, only for DEPT_ADMIN
   */
  public record RoleDef(String employeeCode, String role, String orgUnitCode) {}

  public static final List<RoleDef> ROLE_ASSIGNMENTS =
      List.of(
          new RoleDef(Demo.DEPT_ADMIN_PRODUCTION, "DEPT_ADMIN", "XCT"),
          new RoleDef(Demo.DEPT_ADMIN_JEWELRY, "DEPT_ADMIN", "XKH"),
          new RoleDef(Demo.HC_ADMIN, "HC_ADMIN", null),
          new RoleDef(Demo.SYS_ADMIN, "SYS_ADMIN", null));

  /**
   * @param startDate YYYY-MM-DD
   */
  public record Person(
      String employeeCode,
      String name,
      String email,
      String orgUnitId,
      String dmpCostCenterCode,
      String startDate) {}

  private static final List<String> FAMILY =
      List.of(
          "Nguyễn", "Trần", "Lê", "Phạm", "Hoàng", "Huỳnh", "Phan", "Vũ", "Võ", "Đặng", "Bùi", "Đỗ",
          "Hồ", "Ngô", "Dương", "Lý");
  private static final List<String> MIDDLE_F =
      List.of("Thị", "Ngọc", "Thanh", "Kim", "Hồng", "Thu");
  private static final List<String> MIDDLE_M = List.of("Văn", "Minh", "Quốc", "Hữu", "Đức", "Anh");
  private static final List<String> GIVEN_F =
      List.of(
          "Lan", "Hoa", "Mai", "Linh", "Trang", "Hương", "Thảo", "Yến", "Nhung", "Ngân", "Phương",
          "Oanh");
  private static final List<String> GIVEN_M =
      List.of(
          "Hùng", "Nam", "Long", "Khánh", "Tuấn", "Hải", "Phúc", "Thịnh", "Sơn", "Quân", "Bảo",
          "Khoa");

  /** mulberry32, as in the legacy seed: the same seed gives the same people. */
  static final class Mulberry32 {
    private int state;

    Mulberry32(int seed) {
      this.state = seed;
    }

    double next() {
      state += 0x6d2b79f5;
      int t = state;
      t = (t ^ (t >>> 15)) * (t | 1);
      t ^= t + (t ^ (t >>> 7)) * (t | 61);
      return ((t ^ (t >>> 14)) & 0xffffffffL) / 4294967296.0;
    }

    <T> T pick(List<T> items) {
      return items.get((int) Math.floor(next() * items.size()));
    }
  }

  static String toAscii(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("[\\u0300-\\u036f]", "")
        .replace('đ', 'd')
        .replace('Đ', 'D')
        .toLowerCase(Locale.ROOT);
  }

  /** Largest-remainder allocation of {@code total} seats over positive weights. */
  static int[] allocate(int[] weights, int total) {
    double sum = IntStream.of(weights).sum();
    double[] exact = new double[weights.length];
    int[] counts = new int[weights.length];
    int left = total;
    for (int i = 0; i < weights.length; i++) {
      exact[i] = (weights[i] / sum) * total;
      counts[i] = (int) Math.floor(exact[i]);
      left -= counts[i];
    }
    List<Integer> order =
        IntStream.range(0, weights.length)
            .boxed()
            .sorted(
                Comparator.comparingDouble((Integer i) -> -(exact[i] - Math.floor(exact[i])))
                    .thenComparingInt(i -> i))
            .toList();
    for (int i : order) {
      if (left <= 0) {
        break;
      }
      counts[i]++;
      left--;
    }
    return counts;
  }

  public static List<Person> build(List<SeedOrgTree.Node> nodes) {
    Mulberry32 rand = new Mulberry32(20261019);
    Map<String, SeedOrgTree.Node> byCode = new HashMap<>();
    nodes.forEach(n -> byCode.put(n.dmpCode(), n));
    List<Person> people = new ArrayList<>(COUNT);
    int[] seq = {0};

    for (DemoAccount demo : DEMO_ACCOUNTS) {
      String code = nextCode(seq);
      if (!code.equals(demo.code())) {
        throw new IllegalStateException(
            "Demo account order broken: " + code + " != " + demo.code());
      }
      SeedOrgTree.Node node = byCode.get(demo.node());
      people.add(
          new Person(
              code,
              demo.name(),
              demo.email(),
              node.id(),
              dmpCostCenterFor(demo.node()),
              startDate(rand)));
    }

    List<SeedOrgTree.Node> populated = nodes.stream().filter(n -> n.headcountWeight() > 0).toList();
    int[] counts =
        allocate(
            populated.stream().mapToInt(SeedOrgTree.Node::headcountWeight).toArray(),
            COUNT - DEMO_ACCOUNTS.size());
    for (int i = 0; i < populated.size(); i++) {
      SeedOrgTree.Node node = populated.get(i);
      for (int k = 0; k < counts[i]; k++) {
        String code = nextCode(seq);
        boolean female = rand.next() < 0.55;
        String family = rand.pick(FAMILY);
        String middle = rand.pick(female ? MIDDLE_F : MIDDLE_M);
        String given = rand.pick(female ? GIVEN_F : GIVEN_M);
        String email = toAscii(given) + "." + toAscii(family) + seq[0] + "@pnjp.test";
        people.add(
            new Person(
                code,
                family + " " + middle + " " + given,
                email,
                node.id(),
                dmpCostCenterFor(node.dmpCode()),
                startDate(rand)));
      }
    }
    if (people.size() != COUNT) {
      throw new IllegalStateException("Expected " + COUNT + " employees, got " + people.size());
    }
    return people;
  }

  private static String nextCode(int[] seq) {
    return String.format("PNJP%06d", ++seq[0]);
  }

  private static String dmpCostCenterFor(String nodeCode) {
    return "KHO-NH".equals(nodeCode) ? "CC09" : null;
  }

  private static String startDate(Mulberry32 rand) {
    int year = 2012 + (int) Math.floor(rand.next() * 15);
    int month = 1 + (int) Math.floor(rand.next() * 12);
    int day = 1 + (int) Math.floor(rand.next() * 28);
    return String.format("%d-%02d-%02d", year, month, day);
  }
}
