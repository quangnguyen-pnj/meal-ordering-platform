#!/usr/bin/env node
// Demo data for P4 (reconciliation, finalization, adjustments).
//
// Needs `pnpm db:local`, `pnpm db:seed` and `pnpm dev` running (API on :3001, CLOCK_FAKE_ENABLED=true).
// Usage: node scripts/demo-p4-data.mjs [weekStart=YYYY-MM-DD, a Monday] [--force]
//
// What it makes (week of weekStart, canteen Gò Vấp, through the real API):
//   - 2 dishes + 1 dessert, a published menu for Monday to Friday;
//   - 34 employees (PNJP000001, PNJP000008..040) with dish A every day -> the forecast;
//   - 10 more employees (PNJP000041..050) with dish B every day;
//   - the fake clock on Monday 07:20, the forecast taken, and PNJP000040 adding dish B (an "ADDED" case).
// Then follow docs/demo-p4.md "Preparing a day by hand" with DMP_MOCK_SCENARIO=forgot-to-check-in.
// Registrations are written with SQL (the pg client, no psql needed), like the integration tests; nothing is deleted.
import { createRequire } from 'node:module';

// `pg` comes with embedded-postgres (pnpm db:local), so no psql is needed on the machine.
const { Client } = createRequire(createRequire(import.meta.url).resolve('embedded-postgres'))('pg');

const API = process.env.API_INTERNAL_URL ?? 'http://localhost:3001';
const DB = (process.env.DATABASE_URL ?? 'postgresql://meal:meal_dev_password@localhost:5433/meal').replace(/\?.*$/, '');
const PIN = process.env.SEED_INITIAL_PIN ?? '123456';
const force = process.argv.includes('--force');
const weekStart = process.argv.slice(2).find((a) => /^\d{4}-\d{2}-\d{2}$/.test(a)) ?? nextMonday();

function nextMonday() {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + ((8 - d.getUTCDay()) % 7 || 7));
  return d.toISOString().slice(0, 10);
}
const addDays = (iso, n) => {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};
if (new Date(`${weekStart}T00:00:00Z`).getUTCDay() !== 1) throw new Error(`${weekStart} is not a Monday`);

const db = new Client({ connectionString: DB });
await db.connect();
const psql = async (sql) => {
  const r = await db.query(sql);
  return r.rows[0] ? String(Object.values(r.rows[0])[0]) : '';
};
const code = (n) => `PNJP${String(n).padStart(6, '0')}`;

async function call(cookie, method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { 'content-type': 'application/json', ...(cookie ? { cookie } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return { json: text ? JSON.parse(text) : null, res };
}
async function login(employeeCode) {
  await psql(`UPDATE "Employee" SET "pinMustChange" = false, "pinFailedCount" = 0, "lockedUntil" = NULL WHERE "employeeCode" = '${employeeCode}'`);
  const { res } = await call(null, 'POST', '/api/auth/pin/login', { employeeCode, pin: PIN });
  return res.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
}

// Sessions age with the fake clock, so the clock goes first, then the sign-in.
const publishAt = `${addDays(weekStart, -7)}T10:00:00+07:00`;
let sys = await login(code(4));
await call(sys, 'PUT', '/api/dev/clock', { at: publishAt });
const hc = await login(code(3));

const existing = Number(
  await psql(`SELECT count(*) FROM "Registration" WHERE date BETWEEN '${weekStart}' AND '${addDays(weekStart, 4)}'`),
);
if (existing > 0 && !force) {
  throw new Error(`${existing} registrations already exist in the week of ${weekStart}; use another week or --force`);
}

const stamp = Date.now().toString(36);
const dishIds = [];
for (const [i, name] of ['Cơm gà xối mỡ', 'Cơm sườn nướng'].entries()) {
  const { json } = await call(hc, 'POST', '/api/admin/dishes', {
    canteenId: 'ct_gv',
    name: `${name} (demo P4 ${stamp}-${i})`,
    typeId: 'dt_fac_gv_MAN',
    sessions: ['LUNCH'],
  });
  dishIds.push(json.id);
}
const dessert = (
  await call(hc, 'POST', '/api/admin/desserts', { canteenId: 'ct_gv', name: `Chè đậu xanh (demo P4 ${stamp})` })
).json.id;

const days = [0, 1, 2, 3, 4].map((i) => ({
  date: addDays(weekStart, i),
  isServing: true,
  items: dishIds.map((dishId) => ({ dishId })),
  dessertIds: [dessert],
}));
const week = (await call(hc, 'POST', '/api/admin/menu/weeks/publish', { canteenId: 'ct_gv', weekStart, days })).json;
const items = week.days.map((d) => d.items.map((it) => it.id)); // items[day] = [dishA, dishB]

const groups = [
  { codes: [code(1), ...Array.from({ length: 33 }, (_, k) => code(8 + k))], dish: 0 }, // 34 people, dish A
  { codes: Array.from({ length: 10 }, (_, k) => code(41 + k)), dish: 1 }, // 10 people, dish B
];
const rows = [];
for (let day = 0; day < 5; day++) {
  for (const g of groups) {
    for (const c of g.codes) rows.push(`('${c}', '${items[day][g.dish]}', '${addDays(weekStart, day)}')`);
  }
}
await psql(`
  INSERT INTO "Registration" (id, "employeeId", "menuItemId", date, quantity, source, status, "updatedAt")
  SELECT 'demo_p4_' || gen_random_uuid()::text, e.id, v.item, CAST(v.d AS date), 1, 'SELF', 'ACTIVE', now()
  FROM (VALUES ${rows.join(',')}) AS v(code, item, d)
  JOIN "Employee" e ON e."employeeCode" = v.code
`);

// Monday 07:20: the first request after closeAt takes the forecast snapshot (BR-07).
const monday = weekStart;
await call(sys, 'PUT', '/api/dev/clock', { at: `${monday}T07:20:00+07:00` });
const hcNow = await login(code(3));
await call(hcNow, 'GET', `/api/admin/today?date=${monday}`);
// BR-23: PNJP000040 adds dish B after the forecast -> an "ADDED" case.
await psql(`
  INSERT INTO "Registration" (id, "employeeId", "menuItemId", date, quantity, source, status, "updatedAt")
  SELECT 'demo_p4_' || gen_random_uuid()::text, id, '${items[0][1]}', '${monday}', 1, 'SELF', 'ACTIVE', now()
  FROM "Employee" WHERE "employeeCode" = '${code(40)}'
`);

console.log(`Done. Week ${weekStart}..${addDays(weekStart, 4)}: ${rows.length} registrations, forecast taken for ${monday}.`);
console.log(`Fake clock is ${monday} 07:20 (Asia/Ho_Chi_Minh). Next:`);
console.log('  1. Set DMP_MOCK_SCENARIO=forgot-to-check-in in .env and restart `pnpm dev`.');
console.log(`  2. As PNJP000004 move the clock: PUT /api/dev/clock {"at":"${monday}T08:10:00+07:00"} (13 people provisionally cancelled),`);
console.log(`     then 08:30 -> "Chốt số", 10:00 -> adjustment, 10:30 -> adjustment closed.`);
console.log('  3. Open /admin/reconciliation as PNJP000003 (PIN 123456) and PNJP000002 (Xưởng Chế tác).');

await db.end();
