const base = {
  rootDir: '.',
  testEnvironment: 'node',
  transform: {
    '^.+\.ts$': ['ts-jest', { tsconfig: '<rootDir>/tsconfig.json', diagnostics: true }],
  },
};

/** @type {import('jest').Config} */
module.exports = {
  maxWorkers: 1,
  testTimeout: 60000,
  // "unit" needs no database; "e2e" starts an embedded PostgreSQL, migrates and seeds it once.
  projects: [
    { ...base, displayName: 'unit', testMatch: ['<rootDir>/src/**/*.spec.ts'] },
    {
      ...base,
      displayName: 'e2e',
      testMatch: ['<rootDir>/test/**/*.e2e-spec.ts'],
      globalSetup: '<rootDir>/test/global-setup.ts',
      globalTeardown: '<rootDir>/test/global-teardown.ts',
    },
  ],
};
