-- ─────────────────────────────────────────────────────────────────────
-- V6 — audit_log
-- ─────────────────────────────────────────────────────────────────────
-- Generic, append-only audit trail. One row per mutation (or notable
-- read) on any entity the orchestrator tracks. Polymorphic by design
-- (subject_type + subject_id) so adding new entity types later does
-- not require a schema change.
--
-- `actor_type` restricts the surface of who can originate actions.
-- `before` + `after` carry optional structural diffs. `metadata` is a
-- free-form JSONB bucket for per-action context (request id, reason,
-- correlation id, …).
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE audit_log (
    id           UUID        NOT NULL PRIMARY KEY,
    actor_type   TEXT        NOT NULL,
    actor_id     TEXT        NULL,
    actor_name   TEXT        NULL,
    action       TEXT        NOT NULL,
    subject_type TEXT        NOT NULL,
    subject_id   UUID        NULL,
    before       JSONB       NULL,
    after        JSONB       NULL,
    metadata     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    ip           TEXT        NULL,
    user_agent   TEXT        NULL,
    at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT audit_log_actor_type_values CHECK (
        actor_type IN ('user', 'api_key', 'system', 'worker')
    ),
    CONSTRAINT audit_log_action_not_empty       CHECK (char_length(action) > 0),
    CONSTRAINT audit_log_subject_type_not_empty CHECK (char_length(subject_type) > 0)
);

-- History lookup for a specific subject (dashboard detail pane).
CREATE INDEX audit_log_subject_idx
    ON audit_log (subject_type, subject_id, at DESC)
    WHERE subject_id IS NOT NULL;

-- Who did what, across everything.
CREATE INDEX audit_log_actor_idx
    ON audit_log (actor_type, actor_id, at DESC)
    WHERE actor_id IS NOT NULL;

-- Global "what happened recently" feed.
CREATE INDEX audit_log_at_idx ON audit_log (at DESC);

COMMENT ON TABLE  audit_log              IS 'Append-only audit trail. Polymorphic over subject_type/subject_id.';
COMMENT ON COLUMN audit_log.actor_type   IS 'Who originated the action: user | api_key | system | worker.';
COMMENT ON COLUMN audit_log.action       IS 'Verbed string (e.g. run.triggered, workflow.registered).';
COMMENT ON COLUMN audit_log.before       IS 'Pre-change snapshot when the action mutated state; null otherwise.';
COMMENT ON COLUMN audit_log.after        IS 'Post-change snapshot when the action mutated state; null otherwise.';
COMMENT ON COLUMN audit_log.metadata     IS 'Free-form per-action context (request id, reason, correlation id, …).';
