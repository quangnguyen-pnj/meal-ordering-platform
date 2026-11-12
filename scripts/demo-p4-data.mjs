#!/usr/bin/env node
// Demo data covering every P4 case (reconciliation, finalization, adjustments).
// How to demo with it: docs/demo-p4-data.md.
//
// Needs `pnpm db:local`, `pnpm db:seed` and `pnpm dev` running (API on :3001, CLOCK_FAKE_ENABLED=true),
// with DMP_MOCK_SCENARIO=p4-demo in .env for the attendance cases.
// Usage: node scripts/demo-p4-data.mjs [weekStart=YYYY-MM-DD, a Monday] [--force]
//
// Week of weekStart, Monday to Friday, canteen Gò Vấp. The same registrations every day:
//   Dish A  PNJP000001, 008..040 (34)  most attendance cases -> "Vượt ngưỡng"
//   Dish B  PNJP000042..091 (50)       gains a few meals     -> "Lệch" under the threshold
//   Dish C  PNJP000092..096 (5)        untouched             -> "Khớp"
//   Dish D  nobody before the forecast                      -> "Không có dự trù"
// After the forecast (BR-07), before the lock:
//   024 cancels (NOT_EATING), 025 goes from 2 meals to 1 (REDUCED), 026 swaps A for B (DISH_CHANGED),
//   040 adds B (ADDED), 041 registers D without a forecast (ADDED), 097 gets B from an admin (EXTRA).
// The attendance cases come from the mock DMP scenario p4-demo (see scenarios.json).
// Registrations are written with SQL (the pg client, no psql needed), like the integration tests.
// Nothing is deleted.
import { createRequire } from 'node:module';

// `pg` comes with embedded-postgres (pnpm db:local).
const { Client } = createRequire(createRequire(import.meta.url).resolve('embedded-postgres'))('pg');

const API = process.env.API_INTERNAL_URL ?? 'http://localhost:3001';
const DB = (
  process.env.DATABASE_URL ?? 'postgresql://meal:meal_dev_password@localhost:5433/meal'
).replace(/\?.*$/, '');
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
if (new Date(`${weekStart}T00:00:00Z`).getUTCDay() !== 1)
  throw new Error(`${weekStart} is not a Monday`);
const DAYS = [0, 1, 2, 3, 4].map((i) => addDays(weekStart, i));

const code = (n) => `PNJP${String(n).padStart(6, '0')}`;
const range = (from, to) => Array.from({ length: to - from + 1 }, (_, k) => code(from + k));

const db = new Client({ connectionString: DB });
await db.connect();
const sql = async (text) => (await db.query(text)).rows;

async function call(cookie, method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: { 'content-type': 'application/json', ...(cookie ? { cookie } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

async function login(employeeCode) {
  const unlock = `"pinMustChange" = false, "pinFailedCount" = 0, "lockedUntil" = NULL`;
  await sql(`UPDATE "Employee" SET ${unlock} WHERE "employeeCode" = '${employeeCode}'`);
  const attempt = () =>
    fetch(`${API}/api/auth/pin/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ employeeCode, pin: PIN }),
    });
  let res = await attempt();
  if (res.status === 401) {
    // The PIN was changed on the web. The seed gives every employee the same hash of
    // SEED_INITIAL_PIN, so the most common hash is that PIN: put it back on this demo account.
    console.log(`${employeeCode}: PIN was changed, resetting it to the seed PIN ${PIN}.`);
    await sql(`
      UPDATE "Employee" SET "pinHash" = (
        SELECT "pinHash" FROM "Employee" GROUP BY "pinHash" ORDER BY count(*) DESC LIMIT 1
      ), ${unlock} WHERE "employeeCode" = '${employeeCode}'`);
    res = await attempt();
  }
  if (!res.ok)
    throw new Error(
      `POST /api/auth/pin/login (${employeeCode}) -> ${res.status} ${await res.text()}`,
    );
  return res.headers
    .getSetCookie()
    .map((c) => c.split(';')[0])
    .join('; ');
}

// Sessions age with the fake clock: set the clock (system admin), then sign in.
async function at(vnDateTime) {
  const sys = await login(code(4));
  await call(sys, 'PUT', '/api/dev/clock', { at: `${vnDateTime}:00+07:00` });
  return login(code(3));
}

/** rows: [employeeCode, menuItemId, date, quantity, extraReason?] */
async function register(rows) {
  if (rows.length === 0) return;
  const values = rows
    .map(
      ([c, item, d, q, extra]) =>
        `('${c}', '${item}', '${d}', ${q}, ${extra ? `'${extra}'` : 'NULL'})`,
    )
    .join(',');
  await sql(`
    INSERT INTO "Registration" (id, "employeeId", "menuItemId", date, quantity, source, "isExtra",
        "extraReason", status, "updatedAt")
    SELECT 'demo_p4_' || gen_random_uuid()::text, e.id, v.item, CAST(v.d AS date), v.q,
        CASE WHEN v.extra IS NULL THEN 'SELF' ELSE 'ADMIN' END::"RegistrationSource",
        v.extra IS NOT NULL, v.extra, 'ACTIVE', now()
    FROM (VALUES ${values}) AS v(code, item, d, q, extra)
    JOIN "Employee" e ON e."employeeCode" = v.code`);
}

try {
  const [{ count }] = await sql(
    `SELECT count(*) FROM "Registration" WHERE date BETWEEN '${DAYS[0]}' AND '${DAYS[4]}'`,
  );
  if (Number(count) > 0 && !force) {
    throw new Error(
      `${count} registrations already exist in the week of ${weekStart}; pick another Monday or use --force`,
    );
  }

  // 1. Menu, published the week before.
  let hc = await at(`${addDays(weekStart, -7)}T10:00`);
  const stamp = Date.now().toString(36).slice(-4);
  const dishNames = ['Cơm gà xối mỡ', 'Cơm sườn nướng', 'Bún bò Huế', 'Cơm chay thập cẩm'];
  const dishIds = [];
  for (const name of dishNames) {
    const dish = await call(hc, 'POST', '/api/admin/dishes', {
      canteenId: 'ct_gv',
      name: `${name} (demo ${stamp})`,
      typeId: 'dt_fac_gv_MAN',
      sessions: ['LUNCH'],
    });
    dishIds.push(dish.id);
  }
  const dessert = await call(hc, 'POST', '/api/admin/desserts', {
    canteenId: 'ct_gv',
    name: `Chè đậu xanh (demo ${stamp})`,
  });
  const week = await call(hc, 'POST', '/api/admin/menu/weeks/publish', {
    canteenId: 'ct_gv',
    weekStart,
    days: DAYS.map((date) => ({
      date,
      isServing: true,
      items: dishIds.map((dishId) => ({ dishId })),
      dessertIds: [dessert.id],
    })),
  });
  // item[day][0..3] = menu items of dishes A, B, C, D
  const item = week.days.map((d) => d.items.map((it) => it.id));

  // 2. Registrations before the forecast.
  const before = [];
  DAYS.forEach((d, i) => {
    const [A, B, C] = item[i];
    for (const c of [code(1), ...range(8, 40)]) before.push([c, A, d, c === code(25) ? 2 : 1]);
    for (const c of range(42, 91)) before.push([c, B, d, 1]);
    for (const c of range(92, 96)) before.push([c, C, d, 1]);
  });
  await register(before);

  // 3. Monday 07:20 (after closeAt, before the lock): the first request takes the forecast.
  hc = await at(`${DAYS[0]}T07:20`);
  await call(hc, 'GET', `/api/admin/today?date=${DAYS[0]}`);
  const [{ lines }] = await sql(
    `SELECT count(*) AS lines FROM "ForecastLine" WHERE date BETWEEN '${DAYS[0]}' AND '${DAYS[4]}'`,
  );

  // 4. Changes after the forecast, every day of the week.
  const after = [];
  for (const [i, d] of DAYS.entries()) {
    const [A, B, , D] = item[i];
    await sql(`
      UPDATE "Registration" r SET status = 'CANCELLED', "updatedAt" = now() FROM "Employee" e
      WHERE r."employeeId" = e.id AND r."menuItemId" = '${A}'
        AND e."employeeCode" IN ('${code(24)}', '${code(26)}')`);
    await sql(`
      UPDATE "Registration" r SET quantity = 1, "updatedAt" = now() FROM "Employee" e
      WHERE r."employeeId" = e.id AND r."menuItemId" = '${A}' AND e."employeeCode" = '${code(25)}'`);
    after.push([code(26), B, d, 1]);
    after.push([code(40), B, d, 1]);
    after.push([code(41), D, d, 1]);
    after.push([code(97), B, d, 1, 'Demo: khách của xưởng, HC thêm sau khi khóa']);
  }
  await register(after);

  console.log(
    `Done: week ${DAYS[0]}..${DAYS[4]}, ${before.length + after.length} registrations, ${lines} forecast lines.`,
  );
  console.log(`Fake clock: ${DAYS[0]} 07:20 (Asia/Ho_Chi_Minh). Next steps: docs/demo-p4-data.md`);
} finally {
  await db.end();
}
