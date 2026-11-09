-- P3: attendance and day-D processing (BR-30 to BR-36, plan 6.3).
-- Registrations are never touched by attendance: a provisionally cancelled employee keeps their
-- rows, and their meals stop counting through "DailyPresence" (plan 6.3, rule 7).

CREATE TYPE "PresenceState" AS ENUM ('UNKNOWN', 'PRESENT', 'PROVISIONAL_ABSENT', 'ABSENT');
CREATE TYPE "LeaveDayType" AS ENUM ('FULL', 'AM', 'PM');
CREATE TYPE "CutoffOutcome" AS ENUM ('CANCELLED', 'HELD_SYNC_FAILED', 'HELD_TOO_FEW_PRESENT');

-- One row per employee and serving day, from the first sync that saw them or the cutoff run.
--   firstCheckInAt: earliest check-in of day D seen in DMP (any time; BR-30 decides what counts)
--   dmpConfirmedAt: first sync that saw a manager's confirmation in DMP (BR-30, BR-32, v1.3)
--   provisionalAt / restoredAt: BR-31 cancellation and the latest restore (BR-32, BR-33)
--   confirmedById / confirmedAt / confirmReason: BR-33, the reason is optional
CREATE TABLE "DailyPresence" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "date" DATE NOT NULL,
    "state" "PresenceState" NOT NULL DEFAULT 'UNKNOWN',
    "firstCheckInAt" TIMESTAMP(3),
    "dmpConfirmedAt" TIMESTAMP(3),
    "provisionalAt" TIMESTAMP(3),
    "restoredAt" TIMESTAMP(3),
    "confirmedById" TEXT,
    "confirmedAt" TIMESTAMP(3),
    "confirmReason" TEXT,
    "createdAt" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updatedAt" TIMESTAMP(3) NOT NULL,

    CONSTRAINT "DailyPresence_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "DailyPresence_employeeId_date_key" ON "DailyPresence"("employeeId", "date");
CREATE INDEX "DailyPresence_date_state_idx" ON "DailyPresence"("date", "state");
ALTER TABLE "DailyPresence" ADD CONSTRAINT "DailyPresence_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "DailyPresence" ADD CONSTRAINT "DailyPresence_confirmedById_fkey" FOREIGN KEY ("confirmedById") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- BR-35: leave flags only, never a cancellation. A flag DMP stops returning gets removedAt.
CREATE TABLE "LeaveFlag" (
    "id" TEXT NOT NULL,
    "employeeId" TEXT NOT NULL,
    "date" DATE NOT NULL,
    "dayType" "LeaveDayType" NOT NULL,
    "leaveTypeName" TEXT NOT NULL,
    "syncedAt" TIMESTAMP(3) NOT NULL,
    "removedAt" TIMESTAMP(3),

    CONSTRAINT "LeaveFlag_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "LeaveFlag_employeeId_date_key" ON "LeaveFlag"("employeeId", "date");
CREATE INDEX "LeaveFlag_date_idx" ON "LeaveFlag"("date");
ALTER TABLE "LeaveFlag" ADD CONSTRAINT "LeaveFlag_employeeId_fkey" FOREIGN KEY ("employeeId") REFERENCES "Employee"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- BR-31 / BR-34: what the cutoff run of a factory's day did. One row per day; a re-run by the HC
-- admin after a held run updates it.
CREATE TABLE "AttendanceDay" (
    "id" TEXT NOT NULL,
    "factoryId" TEXT NOT NULL,
    "date" DATE NOT NULL,
    "cutoffRunAt" TIMESTAMP(3) NOT NULL,
    "outcome" "CutoffOutcome" NOT NULL,
    "withMeals" INTEGER NOT NULL,
    "presentWithMeals" INTEGER NOT NULL,
    "cancelledCount" INTEGER NOT NULL,
    "syncRunId" TEXT,

    CONSTRAINT "AttendanceDay_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "AttendanceDay_factoryId_date_key" ON "AttendanceDay"("factoryId", "date");
ALTER TABLE "AttendanceDay" ADD CONSTRAINT "AttendanceDay_factoryId_fkey" FOREIGN KEY ("factoryId") REFERENCES "Factory"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

-- Attendance runs belong to one factory's serving day.
ALTER TABLE "SyncRun" ADD COLUMN "factoryId" TEXT;
ALTER TABLE "SyncRun" ADD COLUMN "date" DATE;
CREATE INDEX "SyncRun_type_date_startedAt_idx" ON "SyncRun"("type", "date", "startedAt");
