-- ─────────────────────────────────────────────────────────────────────
-- V8 — fan-in sibling composite index
-- ─────────────────────────────────────────────────────────────────────
-- After a fan-child completes, the fan coordinator (EPIC 4.5.2) asks:
--
--   SELECT count(*) FROM steps
--    WHERE run_id       = $1
--      AND fan_group_id = $2
--      AND status NOT IN ('completed', 'failed', 'dead_lettered');
--
-- to decide whether the fan-in is ready to release the parent step.
-- A composite on (run_id, fan_group_id, status) lets that query land on
-- a single index lookup; the partial predicate keeps storage lean by
-- only indexing fan children.
-- ─────────────────────────────────────────────────────────────────────

CREATE INDEX steps_fan_group_status_idx
    ON steps (run_id, fan_group_id, status)
    WHERE fan_group_id IS NOT NULL;

COMMENT ON INDEX steps_fan_group_status_idx IS
    'Fan-in sibling readiness check. Composite narrows (run_id, fan_group_id) then filters by status.';
