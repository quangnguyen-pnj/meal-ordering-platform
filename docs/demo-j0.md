# Demo – J0, Java foundation

> Written while both APIs existed (J0–J2). The NestJS API and its commands (`@meal/api-legacy`, `pnpm db:migrate`) were removed in J3; the current setup is in the [README](../README.md).

Each exit criterion of J0 (plan, "J0–J3 – Java migration") and how to see it. Needs a JDK 25 in `JAVA_HOME`; Maven comes with `apps/api/mvnw`.

## `mvnw verify` passes

```bash
cd apps/api
./mvnw verify          # Windows: mvnw.cmd verify
```

Runs the Spotless check and 36 tests: `VnTimeTest` (meal-rules), `AppPropertiesTest`, `DatabaseUrlTest`, `ClockServiceTest`, `ArchitectureTest`, `MigrationTest`, `HttpBasicsTest`. The integration tests start their own PostgreSQL 17, so no database or Docker is needed. From the root, `pnpm lint && pnpm typecheck && pnpm test` runs the same through turbo next to the TypeScript packages.

## The Java API starts on an empty database

```bash
createdb meal_java            # any empty PostgreSQL database
DATABASE_URL=postgresql://meal:meal_dev_password@localhost:5433/meal_java?schema=public \
API_PORT=3002 pnpm --filter @meal/api start:dev
curl -i localhost:3002/api/health      # 200 {"status":"ok"}, Cache-Control: no-store
```

The log shows Flyway applying V1, V2 and V3. Automated: `MigrationTest` (the 23 tables of the legacy schema, the Flyway history 1-2-3, the `ForecastLine` write-once trigger).

## The Java API starts on a database seeded by the legacy API

```bash
cp .env.example .env                    # FLYWAY_BASELINE_ON_MIGRATE=true for development
pnpm --filter @meal/api-legacy db:local # terminal 1: PostgreSQL on :5433
pnpm db:migrate && pnpm db:seed         # legacy schema + 1,700 employees
API_PORT=3002 pnpm --filter @meal/api start:dev
curl localhost:3002/api/health
```

The log shows `Successfully baselined schema with version: 3`; `flyway_schema_history` holds one `BASELINE` row and the seeded data is untouched. With Docker: `docker compose -f docker/docker-compose.yml --env-file .env --profile java up --build`, then `curl localhost:3002/api/health`.

## Errors and headers keep the legacy contract

```bash
curl -i localhost:3002/api/no-such-route   # 404 {"statusCode":404,"code":"NOT_FOUND",...}
curl -i -X POST localhost:3002/api/health  # 404 as well, like NestJS
```

Automated: `HttpBasicsTest` (prefix, headers on success and error, `VALIDATION_FAILED` with the path but not the value sent, a bare 500 for unexpected errors).

## The wall-clock rule bites

Add to any class in `meal-rules` or `meal-api`:

```java
java.time.Instant x() { return java.time.Instant.now(); }
```

`./mvnw verify` fails in `ArchitectureTest.onlyClockServiceReadsTheWallClock`, naming the class and line. `ClockService` is the only exception.

## Production refuses unsafe settings

```bash
NODE_ENV=production pnpm --filter @meal/api start:dev
```

stops at startup listing every problem (mock Microsoft page, example secret, `COOKIE_SECURE`). Automated: `AppPropertiesTest`.
