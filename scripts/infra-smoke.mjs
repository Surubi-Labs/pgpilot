#!/usr/bin/env node
/**
 * Infra smoke test.
 *
 * Connects to the local Postgres instance (started via docker-compose)
 * and runs a SELECT 1 to confirm reachability before the rest of the
 * stack assumes a healthy DB. Exits 0 on success, 1 on failure.
 *
 * Usage: `pnpm run infra:smoke` (or `node scripts/infra-smoke.mjs`).
 * Reads DATABASE_URL from the environment or defaults to the
 * docker-compose connection string.
 */

import pg from "pg";

const { Client } = pg;

const DEFAULT_URL = "postgres://pgpilot:pgpilot@localhost:5432/pgpilot";
const url = process.env.DATABASE_URL ?? DEFAULT_URL;

const client = new Client({ connectionString: url });
const start = Date.now();

try {
  await client.connect();
  const result = await client.query("SELECT 1 AS ok");
  const row = result.rows[0];
  if (!row || row.ok !== 1) {
    throw new Error(`Unexpected row shape: ${JSON.stringify(row)}`);
  }

  const elapsed = Date.now() - start;
  console.warn(`[infra:smoke] ok  ${url}  (${elapsed}ms)`);
  await client.end();
  process.exit(0);
} catch (err) {
  const message = err instanceof Error ? err.message : String(err);
  console.error(`[infra:smoke] FAIL ${url}: ${message}`);
  await client.end().catch(() => {
    /* ignore */
  });
  process.exit(1);
}
