package vn.pnj.meal.api.seed;

import java.util.ArrayList;
import java.util.List;
import vn.pnj.meal.rules.org.OrgNode;

/**
 * Deterministic fake master data for development, tests and the DMP mock: the same factory, floors,
 * cost centers and 6-level org tree as the legacy seed (apps/api-legacy/src/seed-data), id for id.
 *
 * <p>Mapping is only PARTIAL, so inheritance (BR-62) is exercised, and the tree deliberately holds
 * the awkward cases: XSC (Xưởng Sửa chữa) is a meal unit with no floor, so it is invalid (BR-63);
 * KHO (Kho vận) has no meal-unit flag anywhere above, so its people are unassigned (BR-60), and no
 * cost center.
 */
public final class SeedOrgTree {
  private SeedOrgTree() {}

  public static final List<String> DMP_LEVEL_NAMES =
      List.of("Công ty", "Khối", "Phòng/Xưởng", "Bộ phận", "Tổ", "Nhóm");

  public record FactoryDef(
      String id, String code, String name, String dmpCompanyCode, String timezone) {}

  // TODO(open-questions Q1): the real DMP company code is not known yet.
  public static final FactoryDef FACTORY =
      new FactoryDef("fac_gv", "PNJP-GV", "PNJP Gò Vấp", "PNJP", "Asia/Ho_Chi_Minh");

  public record SupplierDef(String id, String name, String unitPrice) {}

  /** Placeholder unit price: the real one is an external dependency (plan section 8, P6). */
  public static final SupplierDef SUPPLIER =
      new SupplierDef("sup_gv", "Nhà cung cấp suất ăn Gò Vấp", "35000.00");

  public record CanteenDef(String id, String code, String name) {}

  public static final CanteenDef CANTEEN = new CanteenDef("ct_gv", "CT-GV", "Nhà ăn Gò Vấp");

  public record DishTypeDef(
      String code,
      String name,
      String color,
      int sortOrder,
      Integer minPerDay,
      Integer maxPerDay) {}

  /** BR-10 / BR-13: dish types. The first three keep the plan's usual mix. */
  public static final List<DishTypeDef> DISH_TYPES =
      List.of(
          new DishTypeDef("MAN", "Mặn", "#b91c1c", 1, 2, 3),
          new DishTypeDef("CHAY", "Chay", "#15803d", 2, 1, 1),
          new DishTypeDef("CHAO", "Cháo", "#b45309", 3, 1, 1),
          new DishTypeDef("EATCLEAN", "Eat clean", "#0369a1", 4, null, null));

  /** Ids follow the migration that turned the old enum into rows. */
  public static String dishTypeId(String code) {
    return "dt_" + FACTORY.id() + "_" + code;
  }

  public record FloorDef(String id, String code, String name, int sortOrder) {}

  public static final List<FloorDef> FLOORS =
      List.of(
          new FloorDef("fl_T1", "T1", "Tầng 1", 1),
          new FloorDef("fl_T2", "T2", "Tầng 2", 2),
          new FloorDef("fl_T3", "T3", "Tầng 3", 3),
          new FloorDef("fl_T4", "T4", "Tầng 4", 4));

  public record CostCenterDef(String id, String code, String name, String source) {}

  public static final List<CostCenterDef> COST_CENTERS =
      List.of(
          new CostCenterDef("cc_CC01", "CC01", "Văn phòng điều hành", "DMP"),
          new CostCenterDef("cc_CC02", "CC02", "Hành chánh", "DMP"),
          new CostCenterDef("cc_CC03", "CC03", "Sản xuất chế tác", "DMP"),
          new CostCenterDef("cc_CC04", "CC04", "Sản xuất kim hoàn", "DMP"),
          new CostCenterDef("cc_CC05", "CC05", "Sửa chữa", "DMP"),
          new CostCenterDef("cc_CC06", "CC06", "Công nghệ thông tin", "DMP"),
          new CostCenterDef("cc_CC07", "CC07", "Dịch vụ và bảo vệ", "DMP"),
          new CostCenterDef("cc_CC08", "CC08", "Kinh doanh", "MANUAL"),
          new CostCenterDef("cc_CC09", "CC09", "Kho vận", "MANUAL"),
          new CostCenterDef("cc_CC10", "CC10", "Chi phí chung", "MANUAL"));

  public static String orgUnitId(String code) {
    return "ou_" + code;
  }

  static String floorId(String code) {
    return "fl_" + code;
  }

  static String costCenterId(String code) {
    return "cc_" + code;
  }

  /**
   * One node of the tree definition.
   *
   * @param meal meal-unit flag set by the HC admin (BR-60)
   * @param hc relative headcount attached directly to this node
   */
  private record Def(
      String code,
      String name,
      boolean meal,
      String floor,
      String cc,
      int hc,
      List<Def> children) {}

  private static Def def(String code, String name, Def... children) {
    return new Def(code, name, false, null, null, 0, List.of(children));
  }

  private static Def def(
      String code, String name, boolean meal, String floor, String cc, int hc, Def... children) {
    return new Def(code, name, meal, floor, cc, hc, List.of(children));
  }

  private static final boolean MEAL = true;
  private static final boolean NO = false;

  private static final Def TREE =
      def(
          "PNJP-GV",
          "PNJP Gò Vấp",
          def(
              "KSX",
              "Khối Sản xuất",
              def(
                  "XCT",
                  "Xưởng Chế tác",
                  NO,
                  "T2",
                  "CC03",
                  0,
                  def(
                      "XCT-A",
                      "Chuyền A",
                      NO,
                      null,
                      null,
                      6,
                      def(
                          "XCT-A1",
                          "Tổ Nhẫn",
                          MEAL,
                          null,
                          null,
                          0,
                          def("XCT-A1-1", "Nhóm Nhẫn 1", NO, null, null, 150),
                          def("XCT-A1-2", "Nhóm Nhẫn 2", NO, null, null, 140)),
                      def(
                          "XCT-A2",
                          "Tổ Dây chuyền",
                          MEAL,
                          "T3",
                          null,
                          0,
                          def("XCT-A2-1", "Nhóm Dây 1", NO, null, null, 130),
                          def("XCT-A2-2", "Nhóm Dây 2", NO, null, null, 120))),
                  def(
                      "XCT-B",
                      "Chuyền B",
                      MEAL,
                      null,
                      null,
                      5,
                      def(
                          "XCT-B1",
                          "Tổ Lắp ráp",
                          NO,
                          null,
                          null,
                          100,
                          def("XCT-B1-1", "Nhóm Lắp ráp 1", NO, null, null, 90),
                          def("XCT-B1-2", "Nhóm Lắp ráp 2", NO, null, null, 80)))),
              def(
                  "XKH",
                  "Xưởng Kim hoàn",
                  NO,
                  "T3",
                  "CC04",
                  0,
                  def(
                      "XKH-DUC",
                      "Bộ phận Đúc",
                      MEAL,
                      null,
                      null,
                      5,
                      def(
                          "XKH-DUC-1",
                          "Tổ Đúc sáp",
                          NO,
                          null,
                          null,
                          20,
                          def("XKH-DUC-1-A", "Nhóm Đúc A", NO, null, null, 60),
                          def("XKH-DUC-1-B", "Nhóm Đúc B", NO, null, null, 50)),
                      def("XKH-DUC-2", "Tổ Hoàn thiện", NO, null, null, 70)),
                  def(
                      "XKH-DA",
                      "Bộ phận Đính đá",
                      NO,
                      null,
                      null,
                      4,
                      def(
                          "XKH-DA-1",
                          "Tổ Đính đá 1",
                          MEAL,
                          null,
                          null,
                          10,
                          def("XKH-DA-1-A", "Nhóm Đính A", NO, null, null, 90),
                          def("XKH-DA-1-B", "Nhóm Đính B", NO, null, null, 80)),
                      def("XKH-DA-2", "Tổ Đính đá 2", MEAL, null, null, 70))),
              // No floor anywhere above: its meal unit is invalid (BR-63).
              def(
                  "XSC",
                  "Xưởng Sửa chữa",
                  NO,
                  null,
                  "CC05",
                  0,
                  def("XSC-1", "Tổ Sửa chữa", MEAL, null, null, 60))),
          def(
              "KVP",
              "Khối Văn phòng",
              NO,
              "T1",
              "CC01",
              0,
              def(
                  "NS",
                  "Phòng Nhân sự",
                  MEAL,
                  null,
                  null,
                  8,
                  def("NS-HC", "Hành chánh", NO, null, "CC02", 25),
                  def("NS-TD", "Tuyển dụng", NO, null, null, 15)),
              def(
                  "KT",
                  "Phòng Kế toán",
                  MEAL,
                  null,
                  null,
                  0,
                  def("KT-TH", "Kế toán tổng hợp", NO, null, null, 20),
                  def("KT-CN", "Kế toán công nợ", NO, null, null, 18)),
              def("IT", "Phòng IT", MEAL, "T4", "CC06", 30),
              def(
                  "KD",
                  "Phòng Kinh doanh",
                  MEAL,
                  "T4",
                  "CC08",
                  0,
                  def("KD-MB", "Kinh doanh miền Bắc", NO, null, null, 40),
                  def("KD-OL", "Kinh doanh online", NO, null, null, 35))),
          def(
              "KHT",
              "Khối Hỗ trợ",
              def("BV", "Tổ Bảo vệ", MEAL, "T1", "CC07", 45),
              def("VS", "Tổ Vệ sinh", MEAL, "T1", "CC07", 40),
              // No meal-unit flag above or at this branch: employees are unassigned (BR-60), and
              // no cost center is mapped for XH (BR-63 "missing cost center").
              def(
                  "KHO",
                  "Kho vận",
                  NO,
                  "T1",
                  null,
                  0,
                  def("KHO-NH", "Nhập hàng", NO, null, null, 25),
                  def("KHO-XH", "Xuất hàng", NO, null, null, 25))));

  /**
   * A seeded org node. {@code path} is "/ou_A/ou_B/" with a trailing slash; {@code headcountWeight}
   * drives the employee generator.
   */
  public record Node(
      String id,
      String factoryId,
      String dmpCode,
      String name,
      int level,
      String dmpLevelName,
      String parentId,
      String path,
      boolean isMealUnit,
      String floorId,
      String canteenId,
      String costCenterId,
      int headcountWeight) {

    public OrgNode toRuleNode() {
      return new OrgNode(id, parentId, isMealUnit, floorId, canteenId, costCenterId);
    }
  }

  /** The whole canteen mapping is set once, at the root; everything below inherits it (BR-62). */
  public static List<Node> build() {
    List<Node> out = new ArrayList<>();
    walk(TREE, null, 1, out);
    return out;
  }

  private static void walk(Def def, Node parent, int level, List<Node> out) {
    String id = orgUnitId(def.code());
    Node node =
        new Node(
            id,
            FACTORY.id(),
            def.code(),
            def.name(),
            level,
            level <= DMP_LEVEL_NAMES.size() ? DMP_LEVEL_NAMES.get(level - 1) : "Cấp " + level,
            parent == null ? null : parent.id(),
            (parent == null ? "/" : parent.path()) + id + "/",
            def.meal(),
            def.floor() == null ? null : floorId(def.floor()),
            parent == null ? CANTEEN.id() : null,
            def.cc() == null ? null : costCenterId(def.cc()),
            def.hc());
    out.add(node);
    for (Def child : def.children()) {
      walk(child, node, level + 1, out);
    }
  }
}
