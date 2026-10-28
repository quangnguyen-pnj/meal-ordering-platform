-- J2: ShedLock keeps each scheduled job (sync-org-employees, menu-publish-warning, close-week,
-- reselect-deadline) to one API instance at a time. The legacy API ran its jobs on pg-boss, in its
-- own "pgboss" schema; this table replaces that once the Java API runs the jobs (J3).
CREATE TABLE "shedlock" (
    "name" VARCHAR(64) NOT NULL,
    "lock_until" TIMESTAMP(3) NOT NULL,
    "locked_at" TIMESTAMP(3) NOT NULL,
    "locked_by" VARCHAR(255) NOT NULL,

    CONSTRAINT "shedlock_pkey" PRIMARY KEY ("name")
);
