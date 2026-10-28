// Runs the Maven wrapper of apps/api with the given arguments, on Windows and elsewhere, so the
// turbo tasks (lint, typecheck, test, build) work the same as for the TypeScript packages.
// MAVEN_CMD overrides the wrapper (e.g. MAVEN_CMD=mvn when a matching Maven is installed).
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const apiRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const isWindows = process.platform === 'win32';
const command = process.env.MAVEN_CMD ?? path.join(apiRoot, isWindows ? 'mvnw.cmd' : 'mvnw');

const result = spawnSync(command, ['-B', ...process.argv.slice(2)], {
  cwd: apiRoot,
  stdio: 'inherit',
  shell: isWindows,
  env: {
    // Local runs read the repository's .env (see application.yml).
    MEAL_ENV_FILE: path.resolve(apiRoot, '../../.env'),
    ...process.env,
  },
});
if (result.error) {
  console.error(`Could not run ${command}: ${result.error.message}`);
  process.exit(1);
}
process.exit(result.status ?? 1);
