#!/bin/sh
# Applies database migrations, optionally seeds fake data (never in production), then starts the API.
set -eu

echo "Applying database migrations..."
./node_modules/.bin/prisma migrate deploy

if [ "${SEED_ON_START:-false}" = "true" ]; then
  if [ "${NODE_ENV:-}" = "production" ]; then
    echo "SEED_ON_START ignored: the seed refuses to run when NODE_ENV=production" >&2
  else
    echo "Seeding development data (idempotent)..."
    ./node_modules/.bin/tsx prisma/seed/seed.ts
  fi
fi

exec node dist/main.js
