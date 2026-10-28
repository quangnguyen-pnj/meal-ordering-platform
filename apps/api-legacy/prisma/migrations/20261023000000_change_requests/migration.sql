-- Change requests from the product owner (2026-10-23), see docs/decisions.md:
--   * dish types become master data (code, name, colour, usual bounds per day),
--   * each canteen has its own dish and dessert library, without duplicate names,
--   * an HC admin can set the floor where one employee picks up their meal,
--   * a registration moved to a replacement dish remembers the item it came from (BR-14).
-- Existing rows are converted, never dropped (plan 7.1 rule 7).

-- Dish types ---------------------------------------------------------------------------------
-- The table takes the enum's name, so the enum steps aside until the dishes are converted.
ALTER TYPE "DishType" RENAME TO "DishType_enum_old";
CREATE TABLE "DishType" (
    "id" TEXT NOT NULL,
    "factoryId" TEXT NOT NULL,
    "code" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "color" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    "minPerDay" INTEGER,
    "maxPerDay" INTEGER,
    "active" BOOLEAN NOT NULL DEFAULT true,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "DishType_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "DishType_factoryId_code_key" ON "DishType"("factoryId", "code");
ALTER TABLE "DishType" ADD CONSTRAINT "DishType_factoryId_fkey" FOREIGN KEY ("factoryId") REFERENCES "Factory"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- The three former enum values, with the plan's usual mix (BR-13: 1 Chay, 1 Cháo, 2-3 Mặn).
INSERT INTO "DishType" ("id", "factoryId", "code", "name", "color", "sortOrder", "minPerDay", "maxPerDay")
SELECT 'dt_' || f."id" || '_' || t.code, f."id", t.code, t.name, t.color, t.sort_order, t.min_per_day, t.max_per_day
FROM "Factory" f
CROSS JOIN (VALUES
  ('MAN',  'Mặn',  '#b91c1c', 1, 2, 3),
  ('CHAY', 'Chay', '#15803d', 2, 1, 1),
  ('CHAO', 'Cháo', '#b45309', 3, 1, 1)
) AS t(code, name, color, sort_order, min_per_day, max_per_day);

ALTER TABLE "Dish" ADD COLUMN "typeId" TEXT;
UPDATE "Dish" d SET "typeId" = 'dt_' || d."factoryId" || '_' || d."type"::text;
ALTER TABLE "Dish" ALTER COLUMN "typeId" SET NOT NULL;
DROP INDEX "Dish_factoryId_type_idx";
ALTER TABLE "Dish" DROP COLUMN "type";
DROP TYPE "DishType_enum_old";
ALTER TABLE "Dish" ADD CONSTRAINT "Dish_typeId_fkey" FOREIGN KEY ("typeId") REFERENCES "DishType"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- One library per canteen ------------------------------------------------------------------
-- An entry goes to the canteen of the first menu week that used it, else to the factory's
-- first canteen by code.
ALTER TABLE "Dish" ADD COLUMN "canteenId" TEXT;
UPDATE "Dish" d SET "canteenId" = COALESCE(
  (SELECT w."canteenId" FROM "MenuItem" i
     JOIN "MenuDay" md ON md."id" = i."menuDayId"
     JOIN "MenuWeek" w ON w."id" = md."menuWeekId"
   WHERE i."dishId" = d."id" ORDER BY w."weekStart", w."createdAt" LIMIT 1),
  (SELECT c."id" FROM "Canteen" c WHERE c."factoryId" = d."factoryId" ORDER BY c."code" LIMIT 1));
ALTER TABLE "Dish" ALTER COLUMN "canteenId" SET NOT NULL;
ALTER TABLE "Dish" ADD CONSTRAINT "Dish_canteenId_fkey" FOREIGN KEY ("canteenId") REFERENCES "Canteen"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "Dish_canteenId_typeId_idx" ON "Dish"("canteenId", "typeId");

ALTER TABLE "Dessert" ADD COLUMN "canteenId" TEXT;
UPDATE "Dessert" x SET "canteenId" = COALESCE(
  (SELECT w."canteenId" FROM "MenuDayDessert" mdd
     JOIN "MenuDay" md ON md."id" = mdd."menuDayId"
     JOIN "MenuWeek" w ON w."id" = md."menuWeekId"
   WHERE mdd."dessertId" = x."id" ORDER BY w."weekStart", w."createdAt" LIMIT 1),
  (SELECT c."id" FROM "Canteen" c WHERE c."factoryId" = x."factoryId" ORDER BY c."code" LIMIT 1));
ALTER TABLE "Dessert" ALTER COLUMN "canteenId" SET NOT NULL;
ALTER TABLE "Dessert" ADD CONSTRAINT "Dessert_canteenId_fkey" FOREIGN KEY ("canteenId") REFERENCES "Canteen"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
CREATE INDEX "Dessert_canteenId_idx" ON "Dessert"("canteenId");

-- No duplicates (the "added twice" bug) ------------------------------------------------------
-- Names were stored as typed; store them trimmed with single spaces, as the API now does.
UPDATE "Dish" SET "name" = regexp_replace(btrim("name"), '\s+', ' ', 'g');
UPDATE "Dessert" SET "name" = regexp_replace(btrim("name"), '\s+', ' ', 'g');
-- Duplicates already in the library: the oldest stays, later copies are archived (menus that
-- use them keep them; archived entries simply cannot be put on a new menu).
UPDATE "Dish" d SET "archivedAt" = COALESCE(d."archivedAt", CURRENT_TIMESTAMP)
WHERE d."archivedAt" IS NULL AND EXISTS (
  SELECT 1 FROM "Dish" o
  WHERE o."archivedAt" IS NULL AND o."canteenId" = d."canteenId" AND o."typeId" = d."typeId"
    AND lower(o."name") = lower(d."name")
    AND (o."createdAt", o."id") < (d."createdAt", d."id"));
UPDATE "Dessert" d SET "archivedAt" = COALESCE(d."archivedAt", CURRENT_TIMESTAMP)
WHERE d."archivedAt" IS NULL AND EXISTS (
  SELECT 1 FROM "Dessert" o
  WHERE o."archivedAt" IS NULL AND o."canteenId" = d."canteenId"
    AND lower(o."name") = lower(d."name")
    AND (o."createdAt", o."id") < (d."createdAt", d."id"));
-- The API also refuses a name that matches an archived entry (restore it instead); the indexes
-- are the safety net against two concurrent requests.
CREATE UNIQUE INDEX "Dish_canteen_type_name_active_key" ON "Dish"("canteenId", "typeId", lower("name"))
  WHERE "archivedAt" IS NULL;
CREATE UNIQUE INDEX "Dessert_canteen_name_active_key" ON "Dessert"("canteenId", lower("name"))
  WHERE "archivedAt" IS NULL;

-- Pick-up floor per employee -----------------------------------------------------------------
ALTER TABLE "Employee" ADD COLUMN "floorOverrideId" TEXT;
ALTER TABLE "Employee" ADD CONSTRAINT "Employee_floorOverrideId_fkey" FOREIGN KEY ("floorOverrideId") REFERENCES "Floor"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- BR-14: where a moved registration came from ----------------------------------------------------
ALTER TABLE "Registration" ADD COLUMN "replacedFromItemId" TEXT;
ALTER TABLE "Registration" ADD CONSTRAINT "Registration_replacedFromItemId_fkey" FOREIGN KEY ("replacedFromItemId") REFERENCES "MenuItem"("id") ON DELETE SET NULL ON UPDATE CASCADE;
