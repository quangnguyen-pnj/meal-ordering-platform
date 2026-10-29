/**
 * Runs a real PostgreSQL without Docker (embedded-postgres), for development on machines that
 * have no Docker. The API's tests start their own throwaway database (or use TEST_DATABASE_URL).
 *
 *   pnpm db:local                                      persistent, data in <repo>/.pg-data
 *   node scripts/local-postgres.mjs --temp --port 54329   throwaway
 *
 * Prints "READY <DATABASE_URL>" once the server accepts connections, then runs until it receives
 * SIGINT/SIGTERM or its stdin closes.
 */
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import EmbeddedPostgres from 'embedded-postgres';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const temp = process.argv.includes('--temp');
const port = Number(arg('port', '5433'));
const database = arg('db', 'meal');
const user = 'meal';
const password = 'meal_dev_password';
const databaseDir = temp
  ? mkdtempSync(path.join(tmpdir(), 'meal-pg-'))
  : path.join(repo, '.pg-data');

const pg = new EmbeddedPostgres({
  databaseDir,
  user,
  password,
  port,
  persistent: !temp,
  // Vietnamese text needs UTF8; initdb would otherwise follow the Windows code page (WIN1252).
  initdbFlags: ['--encoding=UTF8', '--locale=C'],
  onLog: () => undefined,
  onError: (e) => console.error(e),
});

const fresh = temp || !existsSync(path.join(databaseDir, 'PG_VERSION'));
if (fresh) await pg.initialise();
await pg.start();
if (fresh) await pg.createDatabase(database);

let stopping = false;
const stop = async () => {
  if (stopping) return;
  stopping = true;
  await pg.stop().catch(() => undefined);
  if (temp) rmSync(databaseDir, { recursive: true, force: true });
  process.exit(0);
};
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
process.stdin.on('end', stop);
process.stdin.resume();

console.log(`READY postgresql://${user}:${password}@localhost:${port}/${database}?schema=public`);
