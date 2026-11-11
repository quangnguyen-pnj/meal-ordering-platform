-- P4: reconciliation, finalization and adjustments (BR-40 to BR-46, plan 6.3).
-- FinalEmployeeLine, FinalCount and AdjustmentLine are write-once (plan 7.1 rule 7): triggers
-- refuse any UPDATE or DELETE, so a later change can only be a new adjustment (BR-46).

CREATE TYPE "DayCloseStatus" AS ENUM ('OPEN', 'FINALIZED');

-- BR-43: one finalization per factory, day and session. A row exists only once finalized.
--   finalizedById: the HC admin who pressed "Chốt suất"; null when the system finalized the day at
--   close.autoFinalizeAt (product owner, 2026-11-10).
--   cutoffOutcome: the BR-34 state of the cutoff when the day was finalized (null: no cutoff run).
CREATE TABLE "DayClose" (
    "id" TEXT NOT NULL,
    "factoryId" TEXT NOT NULL,
    "date" DATE NOT NULL,
    "session" "MealSession" NOT NULL DEFAULT 'LUNCH',
    "status" "DayCloseStatus" NOT NULL DEFAULT 'FINALIZED',
    "finalizedById" TEXT,
    "finalizedAt" TIMESTAMP(3) NOT NULL,
    "autoFinalized" BOOLEAN NOT NULL DEFAULT false,
    "cutoffOutcome" "CutoffOutcome",
    "cancelledCount" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "DayClose_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "DayClose_actor_check" CHECK ("autoFinalized" OR "finalizedById" IS NOT NULL)
);
CREATE UNIQUE INDEX "DayClose_factoryId_date_session_key" ON "DayClose"("factoryId", "date", "session");
ALTER TABLE "DayClose" ADD CONSTRAINT "DayClose_factoryId_fkey" FOREIGN KEY ("factoryId") REFERENCES "Factory"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "DayClose" ADD CONSTRAINT "DayClose_finalizedById_fkey" FOREIGN KEY ("finalizedById") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- BR-43 / BR-64: per-employee snapshot at finalization, with the mapping in effect then.
CREATE TABLE "FinalEmployeeLine" (
    "id" TEXT NOT NULL,
    "dayCloseId" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "menuItemId" TEXT NOT NULL,
    "quantity" INTEGER NOT NULL,
    "mealUnitId" TEXT,
    "floorId" TEXT,
    "canteenId" TEXT NOT NULL,
    "costCenterId" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "FinalEmployeeLine_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "FinalEmployeeLine_quantity_check" CHECK ("quantity" > 0)
);
CREATE UNIQUE INDEX "FinalEmployeeLine_dayCloseId_employeeId_menuItemId_key" ON "FinalEmployeeLine"("dayCloseId", "employeeId", "menuItemId");
CREATE INDEX "FinalEmployeeLine_employeeId_idx" ON "FinalEmployeeLine"("employeeId");
ALTER TABLE "FinalEmployeeLine" ADD CONSTRAINT "FinalEmployeeLine_dayCloseId_fkey" FOREIGN KEY ("dayCloseId") REFERENCES "DayClose"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "FinalEmployeeLine" ADD CONSTRAINT "FinalEmployeeLine_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "FinalEmployeeLine" ADD CONSTRAINT "FinalEmployeeLine_menuItemId_fkey" FOREIGN KEY ("menuItemId") REFERENCES "MenuItem"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- BR-43: sum of FinalEmployeeLine by meal unit x floor x canteen x meal; the count sent to the
-- caterer. mealUnitId is null for meals of employees without a meal unit (BR-60).
CREATE TABLE "FinalCount" (
    "id" TEXT NOT NULL,
    "dayCloseId" TEXT NOT NULL,
    "mealUnitId" TEXT,
    "floorId" TEXT,
    "canteenId" TEXT NOT NULL,
    "menuItemId" TEXT NOT NULL,
    "quantity" INTEGER NOT NULL,

    CONSTRAINT "FinalCount_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "FinalCount_quantity_check" CHECK ("quantity" > 0)
);
CREATE INDEX "FinalCount_dayCloseId_idx" ON "FinalCount"("dayCloseId");
ALTER TABLE "FinalCount" ADD CONSTRAINT "FinalCount_dayCloseId_fkey" FOREIGN KEY ("dayCloseId") REFERENCES "DayClose"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "FinalCount" ADD CONSTRAINT "FinalCount_menuItemId_fkey" FOREIGN KEY ("menuItemId") REFERENCES "MenuItem"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- BR-46: a change to a finalized day, by the HC admin, with a reason; the caterer's confirmation is
-- required when a line adds meals. number: 1, 2, ... per day, the supplementary file's number.
CREATE TABLE "Adjustment" (
    "id" TEXT NOT NULL,
    "dayCloseId" TEXT NOT NULL,
    "number" INTEGER NOT NULL,
    "createdById" TEXT NOT NULL,
    "createdAt" TIMESTAMP(3) NOT NULL,
    "reason" TEXT NOT NULL,
    "catererConfirmed" BOOLEAN NOT NULL,

    CONSTRAINT "Adjustment_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "Adjustment_reason_check" CHECK (length(btrim("reason")) > 0)
);
CREATE UNIQUE INDEX "Adjustment_dayCloseId_number_key" ON "Adjustment"("dayCloseId", "number");
ALTER TABLE "Adjustment" ADD CONSTRAINT "Adjustment_dayCloseId_fkey" FOREIGN KEY ("dayCloseId") REFERENCES "DayClose"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "Adjustment" ADD CONSTRAINT "Adjustment_createdById_fkey" FOREIGN KEY ("createdById") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- BR-46 / BR-64: delta may be negative; effective count = FinalCount + sum of delta.
CREATE TABLE "AdjustmentLine" (
    "id" TEXT NOT NULL,
    "adjustmentId" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "menuItemId" TEXT NOT NULL,
    "delta" INTEGER NOT NULL,
    "mealUnitId" TEXT,
    "floorId" TEXT,
    "canteenId" TEXT NOT NULL,
    "costCenterId" TEXT,

    CONSTRAINT "AdjustmentLine_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "AdjustmentLine_delta_check" CHECK ("delta" <> 0)
);
CREATE INDEX "AdjustmentLine_adjustmentId_idx" ON "AdjustmentLine"("adjustmentId");
CREATE INDEX "AdjustmentLine_employeeId_idx" ON "AdjustmentLine"("employeeId");
ALTER TABLE "AdjustmentLine" ADD CONSTRAINT "AdjustmentLine_adjustmentId_fkey" FOREIGN KEY ("adjustmentId") REFERENCES "Adjustment"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "AdjustmentLine" ADD CONSTRAINT "AdjustmentLine_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "AdjustmentLine" ADD CONSTRAINT "AdjustmentLine_menuItemId_fkey" FOREIGN KEY ("menuItemId") REFERENCES "MenuItem"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- Plan 7.1 rule 7: the snapshots and adjustment records are never changed or deleted, like
-- ForecastLine (V2's "reject_snapshot_change").
CREATE TRIGGER "FinalEmployeeLine_write_once" BEFORE UPDATE OR DELETE ON "FinalEmployeeLine"
    FOR EACH ROW EXECUTE FUNCTION "reject_snapshot_change"();
CREATE TRIGGER "FinalCount_write_once" BEFORE UPDATE OR DELETE ON "FinalCount"
    FOR EACH ROW EXECUTE FUNCTION "reject_snapshot_change"();
CREATE TRIGGER "Adjustment_write_once" BEFORE UPDATE OR DELETE ON "Adjustment"
    FOR EACH ROW EXECUTE FUNCTION "reject_snapshot_change"();
CREATE TRIGGER "AdjustmentLine_write_once" BEFORE UPDATE OR DELETE ON "AdjustmentLine"
    FOR EACH ROW EXECUTE FUNCTION "reject_snapshot_change"();

-- BR-43 / BR-32 (v1.4): when a provisional cancellation became final, and what attendance arrived
-- for a cancelled employee after finalization (recorded only, a case to review).
--   absentAt: finalization turned PROVISIONAL_ABSENT into ABSENT
--   lateCheckInAt: first sync after finalization that saw a check-in for an ABSENT employee
ALTER TABLE "DailyPresence" ADD COLUMN "absentAt" TIMESTAMP(3);
ALTER TABLE "DailyPresence" ADD COLUMN "lateCheckInAt" TIMESTAMP(3);
