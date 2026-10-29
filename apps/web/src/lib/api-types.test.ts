import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const web = path.resolve(__dirname, '../..');

describe('API types (J3)', () => {
  it('src/lib/openapi.d.ts is generated from the committed apps/api/openapi.json', () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'meal-openapi-'));
    try {
      const out = path.join(dir, 'openapi.d.ts');
      execFileSync(
        path.join(web, 'node_modules/.bin/openapi-typescript'),
        ['../api/openapi.json', '-o', out],
        { cwd: web, stdio: 'pipe' },
      );
      expect(
        readFileSync(path.join(web, 'src/lib/openapi.d.ts'), 'utf8'),
        'stale: run `pnpm --filter @meal/web api:types`',
      ).toBe(readFileSync(out, 'utf8'));
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }, 30_000);
});
