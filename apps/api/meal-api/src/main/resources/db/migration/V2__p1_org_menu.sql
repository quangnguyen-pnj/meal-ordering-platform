-- CreateEnum
CREATE TYPE "DishType" AS ENUM ('CHAY', 'CHAO', 'MAN');

-- CreateEnum
CREATE TYPE "MealSession" AS ENUM ('LUNCH');

-- CreateEnum
CREATE TYPE "MenuWeekStatus" AS ENUM ('DRAFT', 'PUBLISHED', 'CLOSED');

-- CreateEnum
CREATE TYPE "RegistrationStatus" AS ENUM ('ACTIVE', 'NEEDS_RESELECT', 'CANCELLED');

-- CreateEnum
CREATE TYPE "RegistrationSource" AS ENUM ('SELF', 'ADMIN');

-- CreateEnum
CREATE TYPE "NotificationType" AS ENUM ('MENU_DISH_REPLACED', 'MENU_DISH_REMOVED');

-- CreateEnum
CREATE TYPE "MappingImportStatus" AS ENUM ('PREVIEW', 'REJECTED', 'APPLIED');

-- AlterTable
ALTER TABLE "OrgUnit" ADD COLUMN     "dmpCostCenterCode" TEXT;

-- AlterTable
ALTER TABLE "RoleAssignment" ADD COLUMN     "revokedAt" TIMESTAMP(3);

-- AlterTable
ALTER TABLE "SyncRun" ADD COLUMN     "details" JSONB;

-- CreateTable
CREATE TABLE "Dish" (
    "id" TEXT NOT NULL,
    "factoryId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "type" "DishType" NOT NULL,
    "sessions" "MealSession"[],
    "archivedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Dish_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Dessert" (
    "id" TEXT NOT NULL,
    "factoryId" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "archivedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "Dessert_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MenuWeek" (
    "id" TEXT NOT NULL,
    "factoryId" TEXT NOT NULL,
    "canteenId" TEXT NOT NULL,
    "weekStart" DATE NOT NULL,
    "status" "MenuWeekStatus" NOT NULL DEFAULT 'DRAFT',
    "publishedAt" TIMESTAMP(3),
    "publishedById" TEXT,
    "closeAt" TIMESTAMP(3),
    "closedAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "MenuWeek_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MenuDay" (
    "id" TEXT NOT NULL,
    "menuWeekId" TEXT NOT NULL,
    "date" DATE NOT NULL,
    "isServing" BOOLEAN NOT NULL,

    CONSTRAINT "MenuDay_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MenuItem" (
    "id" TEXT NOT NULL,
    "menuDayId" TEXT NOT NULL,
    "session" "MealSession" NOT NULL DEFAULT 'LUNCH',
    "dishId" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL,
    "removedAt" TIMESTAMP(3),
    "replacedByItemId" TEXT,

    CONSTRAINT "MenuItem_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MenuDayDessert" (
    "id" TEXT NOT NULL,
    "menuDayId" TEXT NOT NULL,
    "dessertId" TEXT NOT NULL,
    "sortOrder" INTEGER NOT NULL DEFAULT 0,
    "removedAt" TIMESTAMP(3),

    CONSTRAINT "MenuDayDessert_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Registration" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "menuItemId" TEXT NOT NULL,
    "date" DATE NOT NULL,
    "session" "MealSession" NOT NULL DEFAULT 'LUNCH',
    "quantity" INTEGER NOT NULL,
    "source" "RegistrationSource" NOT NULL,
    "isExtra" BOOLEAN NOT NULL DEFAULT false,
    "extraReason" TEXT,
    "status" "RegistrationStatus" NOT NULL DEFAULT 'ACTIVE',
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Registration_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ForecastLine" (
    "id" TEXT NOT NULL,
    "menuWeekId" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "date" DATE NOT NULL,
    "session" "MealSession" NOT NULL,
    "menuItemId" TEXT NOT NULL,
    "quantity" INTEGER NOT NULL,
    "mealUnitId" TEXT,
    "floorId" TEXT,
    "canteenId" TEXT,
    "costCenterId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ForecastLine_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "Notification" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "type" "NotificationType" NOT NULL,
    "payload" JSONB NOT NULL,
    "readAt" TIMESTAMP(3),
    "createdAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "Notification_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "MappingImport" (
    "id" TEXT NOT NULL,
    "factoryId" TEXT NOT NULL,
    "fileName" TEXT NOT NULL,
    "uploadedById" TEXT NOT NULL,
    "status" "MappingImportStatus" NOT NULL,
    "rowCount" INTEGER NOT NULL,
    "errorCount" INTEGER NOT NULL,
    "rows" JSONB NOT NULL,
    "errors" JSONB NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL,
    "appliedAt" TIMESTAMP(3),

    CONSTRAINT "MappingImport_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "Dish_factoryId_type_idx" ON "Dish"("factoryId", "type");

-- CreateIndex
CREATE INDEX "MenuWeek_factoryId_weekStart_idx" ON "MenuWeek"("factoryId", "weekStart");

-- CreateIndex
CREATE UNIQUE INDEX "MenuWeek_canteenId_weekStart_key" ON "MenuWeek"("canteenId", "weekStart");

-- CreateIndex
CREATE UNIQUE INDEX "MenuDay_menuWeekId_date_key" ON "MenuDay"("menuWeekId", "date");

-- CreateIndex
CREATE INDEX "MenuItem_menuDayId_idx" ON "MenuItem"("menuDayId");

-- CreateIndex
CREATE INDEX "MenuDayDessert_menuDayId_idx" ON "MenuDayDessert"("menuDayId");

-- CreateIndex
CREATE INDEX "Registration_date_session_idx" ON "Registration"("date", "session");

-- CreateIndex
CREATE UNIQUE INDEX "Registration_employeeId_menuItemId_key" ON "Registration"("employeeId", "menuItemId");

-- CreateIndex
CREATE INDEX "ForecastLine_menuWeekId_idx" ON "ForecastLine"("menuWeekId");

-- CreateIndex
CREATE INDEX "ForecastLine_date_session_idx" ON "ForecastLine"("date", "session");

-- CreateIndex
CREATE INDEX "Notification_employeeId_readAt_idx" ON "Notification"("employeeId", "readAt");

-- AddForeignKey
ALTER TABLE "Dish" ADD CONSTRAINT "Dish_factoryId_fkey" FOREIGN KEY ("factoryId") REFERENCES "Factory"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Dessert" ADD CONSTRAINT "Dessert_factoryId_fkey" FOREIGN KEY ("factoryId") REFERENCES "Factory"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MenuWeek" ADD CONSTRAINT "MenuWeek_factoryId_fkey" FOREIGN KEY ("factoryId") REFERENCES "Factory"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MenuWeek" ADD CONSTRAINT "MenuWeek_canteenId_fkey" FOREIGN KEY ("canteenId") REFERENCES "Canteen"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MenuDay" ADD CONSTRAINT "MenuDay_menuWeekId_fkey" FOREIGN KEY ("menuWeekId") REFERENCES "MenuWeek"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MenuItem" ADD CONSTRAINT "MenuItem_menuDayId_fkey" FOREIGN KEY ("menuDayId") REFERENCES "MenuDay"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MenuItem" ADD CONSTRAINT "MenuItem_dishId_fkey" FOREIGN KEY ("dishId") REFERENCES "Dish"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MenuItem" ADD CONSTRAINT "MenuItem_replacedByItemId_fkey" FOREIGN KEY ("replacedByItemId") REFERENCES "MenuItem"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MenuDayDessert" ADD CONSTRAINT "MenuDayDessert_menuDayId_fkey" FOREIGN KEY ("menuDayId") REFERENCES "MenuDay"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "MenuDayDessert" ADD CONSTRAINT "MenuDayDessert_dessertId_fkey" FOREIGN KEY ("dessertId") REFERENCES "Dessert"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Registration" ADD CONSTRAINT "Registration_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Registration" ADD CONSTRAINT "Registration_menuItemId_fkey" FOREIGN KEY ("menuItemId") REFERENCES "MenuItem"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ForecastLine" ADD CONSTRAINT "ForecastLine_menuWeekId_fkey" FOREIGN KEY ("menuWeekId") REFERENCES "MenuWeek"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ForecastLine" ADD CONSTRAINT "ForecastLine_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ForecastLine" ADD CONSTRAINT "ForecastLine_menuItemId_fkey" FOREIGN KEY ("menuItemId") REFERENCES "MenuItem"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "Notification" ADD CONSTRAINT "Notification_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;


-- Plan 7.1 rule 7 / BR-07: ForecastLine is write-once. Changes after the snapshot go through
-- registrations (before finalization) or Adjustment (after it, BR-46); the snapshot never moves.
CREATE FUNCTION "reject_snapshot_change"() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '% rows are write-once (plan 7.1 rule 7)', TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER "ForecastLine_write_once"
  BEFORE UPDATE OR DELETE ON "ForecastLine"
  FOR EACH ROW EXECUTE FUNCTION "reject_snapshot_change"();
