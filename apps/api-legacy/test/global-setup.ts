import { execFileSync, spawn, type ChildProcess } from 'node:child_process';
import net from 'node:net';
import path from 'node:path';

const apiRoot = path.resolve(__dirname, '..');

declare global {
  var __MEAL_PG__: ChildProcess | undefined;
}

function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, () => {
      const { port } = server.address() as net.AddressInfo;
      server.close(() => resolve(port));
    });
  });
}

function runNode(args: string[], env: NodeJS.ProcessEnv): void {
  execFileSync(process.execPath, args, { cwd: apiRoot, env, stdio: 'pipe' });
}

/**
 * Starts a throwaway PostgreSQL (embedded, no Docker needed), applies the migrations and seeds
 * the fake data once for the whole suite. Set TEST_DATABASE_URL to use an existing, empty,
 * disposable database instead.
 */
export default async function globalSetup(): Promise<void> {
  let url = process.env.TEST_DATABASE_URL;

  if (!url) {
    const port = await freePort();
    const tsxCli = require.resolve('tsx/cli');
    const child = spawn(
      process.execPath,
      [tsxCli, 'scripts/local-postgres.ts', '--temp', '--port', String(port), '--db', 'meal_test'],
      {
        cwd: apiRoot,
        stdio: ['pipe', 'pipe', 'inherit'],
      },
    );
    globalThis.__MEAL_PG__ = child;
    url = await new Promise<string>((resolve, reject) => {
      let buffer = '';
      const timer = setTimeout(
        () => reject(new Error('Timed out starting embedded PostgreSQL')),
        120_000,
      );
      child.once('exit', (code) =>
        reject(new Error(`Embedded PostgreSQL exited early (code ${code})`)),
      );
      child.stdout?.on('data', (chunk: Buffer) => {
        buffer += chunk.toString();
        const match = /READY (\S+)/.exec(buffer);
        if (match?.[1]) {
          clearTimeout(timer);
          resolve(match[1]);
        }
      });
    });
  }

  process.env.DATABASE_URL = url;
  process.env.TEST_DATABASE_URL = url;

  const env: NodeJS.ProcessEnv = {
    ...process.env,
    DATABASE_URL: url,
    SEED_INITIAL_PIN: '123456',
    NODE_ENV: 'test',
  };
  delete env.FIRST_SYSTEM_ADMIN_CODE;
  runNode([require.resolve('prisma/build/index.js'), 'migrate', 'deploy'], env);
  runNode([require.resolve('tsx/cli'), 'prisma/seed/seed.ts'], env);
}
