-- ─────────────────────────────────────────────────────────────────────
-- V0 — pgpilot schema baseline
-- ─────────────────────────────────────────────────────────────────────
-- Creates the isolated `pgpilot` schema inside the customer's database.
-- Subsequent migrations (V1..VN) create tables, indexes, and triggers
-- inside this schema. Deprovisioning is a single DROP SCHEMA pgpilot
-- CASCADE, so operators can churn the schema without touching customer
-- data in other schemas.
-- ─────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS pgpilot;

COMMENT ON SCHEMA pgpilot IS
    'PgPilot workflow orchestration schema. Managed by Flyway (V0..VN).';
