import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import prettier from 'eslint-config-prettier';

export default tseslint.config(
  {
    ignores: [
      '**/dist/**',
      '**/.next/**',
      '**/node_modules/**',
      '**/coverage/**',
      '**/next-env.d.ts',
      '**/*.config.js',
      // Generated from apps/api/openapi.json (pnpm --filter @meal/web api:types).
      'apps/web/src/lib/openapi.d.ts',
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    rules: {
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/consistent-type-imports': [
        'error',
        { fixStyle: 'inline-type-imports', prefer: 'type-imports' },
      ],
      // Plan 7.1 rule 3: business code never reads the wall clock; use ClockService.
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.object.name='Date'][callee.property.name='now']",
          message:
            'Do not call Date.now(): inject ClockService and pass `now` into rule functions (plan 7.1 rule 3).',
        },
        {
          selector: "NewExpression[callee.name='Date'][arguments.length=0]",
          message:
            'Do not call new Date(): inject ClockService and pass `now` into rule functions (plan 7.1 rule 3).',
        },
      ],
    },
  },
  {
    // Nest providers are resolved by constructor parameter types at runtime, so these
    // imports must stay value imports.
    files: ['apps/api-legacy/**/*.ts'],
    rules: {
      '@typescript-eslint/consistent-type-imports': 'off',
    },
  },
  {
    // The clock itself, tests and seed scripts are the only places allowed to read the wall clock.
    files: [
      '**/clock.service.ts',
      '**/*.spec.ts',
      '**/*.test.ts',
      '**/*.test.tsx',
      'apps/api-legacy/test/**',
      'apps/api-legacy/prisma/**',
    ],
    rules: { 'no-restricted-syntax': 'off' },
  },
  prettier,
);
