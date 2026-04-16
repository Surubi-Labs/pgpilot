-- ─────────────────────────────────────────────────────────────────────
-- V2 — runs
-- ─────────────────────────────────────────────────────────────────────
-- A single workflow execution. The orchestrator claims runs via
-- FOR UPDATE SKIP LOCKED (EPIC 3) using (status, scheduled_at); the
-- partial unique index on idempotency_key dedupes customer-side retries
-- of pgpilot.trigger(...).
--
-- Ancestry: every run tracks its direct parent and the root of its run
-- tree. `depth` is 0 for root runs, +1 per nesting level. This lets
-- the dashboard pull a full sub-workflow graph with a single indexed
-- lookup instead of a recursive CTE.
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE runs (
    id               UUID        NOT NULL PRIMARY KEY,
    workflow_id      UUID        NOT NULL,
    status           TEXT        NOT NULL,
    idempotency_key  TEXT        NULL,
    input            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    output           JSONB       NULL,
    error            JSONB       NULL,

    parent_run_id    UUID        NULL,
    root_run_id      UUID        NULL,
    depth            INTEGER     NOT NULL DEFAULT 0,

    scheduled_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ NULL,
    completed_at     TIMESTAMPTZ NULL,

    claimed_by       TEXT        NULL,
    claimed_at       TIMESTAMPTZ NULL,
    heartbeat_at     TIMESTAMPTZ NULL,

    attempt          INTEGER     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT runs_workflow_fk
        FOREIGN KEY (workflow_id) REFERENCES workflows (id) ON DELETE RESTRICT,
    CONSTRAINT runs_parent_fk
        FOREIGN KEY (parent_run_id) REFERENCES runs (id) ON DELETE RESTRICT,
    CONSTRAINT runs_root_fk
        FOREIGN KEY (root_run_id) REFERENCES runs (id) ON DELETE RESTRICT,

    CONSTRAINT runs_status_values CHECK (
        status IN ('pending', 'running', 'completed', 'failed', 'cancelled')
    ),
    CONSTRAINT runs_depth_non_negative CHECK (depth >= 0),
    CONSTRAINT runs_attempt_non_negative CHECK (attempt >= 0),
    CONSTRAINT runs_claim_fields_match CHECK (
        (claimed_by IS NULL) = (claimed_at IS NULL)
    ),
    CONSTRAINT runs_completed_at_set_for_terminal CHECK (
        (status NOT IN ('completed', 'failed', 'cancelled')) OR (completed_at IS NOT NULL)
    )
);

-- Dedup key for pgpilot.trigger(...) retries. Partial index so the
-- absence of a key does not cost any storage.
CREATE UNIQUE INDEX runs_idempotency_key_uniq
    ON runs (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Claim loop — see EPIC 3 STORY 3.1.1. Partial index matches the
-- SKIP LOCKED claim query (WHERE status = 'pending' AND scheduled_at <= now()).
CREATE INDEX runs_claim_idx
    ON runs (scheduled_at)
    WHERE status = 'pending';

-- Ancestry queries: "all runs in this tree".
CREATE INDEX runs_root_run_id_idx ON runs (root_run_id);
CREATE INDEX runs_parent_run_id_idx ON runs (parent_run_id);

-- Lookup by the workflow definition that produced the run.
CREATE INDEX runs_workflow_id_idx ON runs (workflow_id);

COMMENT ON TABLE  runs                 IS 'Workflow executions. Claimed by orchestrator workers via SKIP LOCKED.';
COMMENT ON COLUMN runs.idempotency_key IS 'Optional customer-supplied key for deduping trigger() retries.';
COMMENT ON COLUMN runs.root_run_id     IS 'Top of the run tree; equals id for root runs, ancestor id for nested runs.';
COMMENT ON COLUMN runs.depth           IS '0 for root runs, +1 per step.invoke() nesting level.';
COMMENT ON COLUMN runs.claimed_by      IS 'Opaque worker id that currently owns the run, or NULL if unclaimed.';
COMMENT ON COLUMN runs.heartbeat_at    IS 'Last heartbeat from the owning worker; drives stale-claim reclamation.';
