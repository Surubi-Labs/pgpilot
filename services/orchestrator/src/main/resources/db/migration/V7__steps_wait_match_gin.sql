-- ─────────────────────────────────────────────────────────────────────
-- V7 — GIN index on steps.wait_match for JSONB containment
-- ─────────────────────────────────────────────────────────────────────
-- The event matcher (EPIC 4.4.3) resumes waiting steps with a query of
-- the shape:
--
--   SELECT s.id, s.run_id
--     FROM steps s
--    WHERE s.status = 'waiting'
--      AND s.wait_event_type = $1
--      AND s.wait_match @> $2::jsonb;
--
-- `jsonb_ops` covers @> / <@ / ? / ?& / ?| and supports partial indexes
-- so we only pay the storage cost on rows that actually have a wait
-- subscription pending.
-- ─────────────────────────────────────────────────────────────────────

CREATE INDEX steps_wait_match_gin_idx
    ON steps USING GIN (wait_match jsonb_ops)
    WHERE wait_match IS NOT NULL AND status = 'waiting';

COMMENT ON INDEX steps_wait_match_gin_idx IS
    'Event matcher index: jsonb containment (@>) for WAITING steps. Partial to keep storage tight.';
