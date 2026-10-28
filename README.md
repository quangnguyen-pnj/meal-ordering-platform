# PNJP Meal Ordering System

Replaces the Excel-and-paper lunch ordering at the PNJP Go Vap factory: employees register a week ahead, the system cancels meals for absent employees from DMP attendance, and the HC admin finalizes the count at 08:30 and exports a file for the caterer.

Status: **Phase 0 (foundation)**, **Phase 1 (org structure and menu)** and **Phase 2 (registration)** are built, plus the change requests of 2026-10-23 (dish types, per-canteen libraries, pick-up floor, Excel imports; see `docs/decisions.md`). The backend is moving from NestJS to **Java 25 + Spring Boot 4** (plan v1.2, phases J0–J3): **J0 (Java foundation)**, **J1 (P0 on Java: sign-in, roles, employees)** and **J2 (P1 on Java: org, master data, dishes, menus, settings, jobs)** are built; the web still needs the NestJS API for the P2 (registration) screens until J3. Attendance, reconciliation, delivery and reports follow in P3 to P7, in Java.

## Layout

| Path              | What                                                                                       |
| ----------------- | ------------------------------------------------------------------------------------------ |
| `apps/api`        | Java 25 + Spring Boot API (Maven): `meal-rules` (pure BR rules), `meal-api` (app, Flyway)  |
| `apps/api-legacy` | NestJS API of P0–P2, Prisma, seed; serves the web until J3, then removed                   |
| `apps/web`        | Next.js app (login, menus, registration, admin screens)                                    |
| `packages/shared` | Zod schemas, enums and rule functions of the TypeScript side                               |
| `docker`          | Dockerfiles, Compose file, nginx config                                                    |
| `docs`            | `plan.md`, `decisions.md`, `open-questions.md`, `security.md`, `demo-p0.md` … `demo-j1.md` |

Working rules for contributors and agents are in [AGENTS.md](AGENTS.md).

## Quick start (no Docker)

Needs Node >= 22.12, pnpm 9 and a JDK 25 (`JAVA_HOME`; Maven comes with `apps/api/mvnw`). Keep the checkout path short on Windows (see `docs/decisions.md`).

```bash
pnpm install
cp .env.example .env
pnpm --filter @meal/api-legacy db:local   # PostgreSQL on :5433, keep it running
pnpm db:migrate && pnpm db:seed
pnpm dev                           # legacy API :3001, web :3000
API_PORT=3002 pnpm --filter @meal/api start:dev   # Java API :3002 on the same database
```

Sign in at http://localhost:3000 with `PNJP000001` and the PIN `123456`; you are asked to change it. More accounts and the demo scripts: [docs/demo-p0.md](docs/demo-p0.md), [docs/demo-p1.md](docs/demo-p1.md), [docs/demo-p2.md](docs/demo-p2.md), [docs/demo-cr-2026-10-23.md](docs/demo-cr-2026-10-23.md).

## Quick start (Docker)

```bash
cp .env.example .env
docker compose -f docker/docker-compose.yml --env-file .env up --build
```

Open http://localhost:8080. Add `--profile java` to also start the Java API on http://localhost:3002. The compose stack is development-only (mock Microsoft sign-in, fake time, seeded data).

## Checks

```bash
pnpm lint
pnpm typecheck
pnpm test        # API tests start a throwaway PostgreSQL (or set TEST_DATABASE_URL)
cd apps/api && ./mvnw verify   # the Java API alone
pnpm build
```

## Notes

- `docs/plan.md` is the MVP plan and the source of truth for every phase.
- All user-facing copy is Vietnamese and lives in `apps/web/src/messages/vi.ts`.
- `docs/security.md` is the security checklist every change goes through. Production must run on HTTPS (`COOKIE_SECURE=true`; the api refuses to start otherwise).
