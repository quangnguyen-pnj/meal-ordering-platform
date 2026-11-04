-- CR 2026-11-04: an employee whose department has a canteen but no usable meal unit (none flagged
-- above them, or theirs has no floor) still registers; their meals go to the factory's fallback
-- meal unit ("đầu mối nhận cơm tập trung"), picked by the HC admin. At most one per factory.
ALTER TABLE "OrgUnit" ADD COLUMN "isFallbackMealUnit" BOOLEAN NOT NULL DEFAULT false;

CREATE UNIQUE INDEX "OrgUnit_factoryId_fallbackMealUnit_key"
    ON "OrgUnit" ("factoryId") WHERE "isFallbackMealUnit";

ALTER TABLE "OrgUnit" ADD CONSTRAINT "OrgUnit_fallback_is_meal_unit_check"
    CHECK (NOT "isFallbackMealUnit" OR "isMealUnit");
