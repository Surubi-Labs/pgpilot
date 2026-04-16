-- ─────────────────────────────────────────────────────────────────────
-- V0 — pgpilot schema baseline
-- ─────────────────────────────────────────────────────────────────────
-- Flyway creates the target schema (see `createSchemas=true`); this
-- migration just documents it. All subsequent migrations (V1..VN) create
-- tables unqualified and rely on Flyway's search_path setup so the
-- orchestrator can point at any schema name in the customer's database
-- — defaulting to `pgpilot` but tunable per deployment.
--
-- Deprovisioning is a single `DROP SCHEMA <name> CASCADE`, so operators
-- can churn the schema without touching customer data elsewhere.
-- ─────────────────────────────────────────────────────────────────────

COMMENT ON SCHEMA "${flyway:defaultSchema}" IS
    'PgPilot workflow orchestration schema. Managed by Flyway (V0..VN).';
