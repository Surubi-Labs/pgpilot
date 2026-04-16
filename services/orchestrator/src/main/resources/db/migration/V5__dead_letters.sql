-- ─────────────────────────────────────────────────────────────────────
-- V5 — dead_letters
-- ─────────────────────────────────────────────────────────────────────
-- Audit trail for runs and steps that exhausted retries (or were
-- explicitly dead-lettered by an operator). Linked back to the
-- originating row via run_id and/or step_id — at least one must be set.
--
-- Rows are kept for operator replay: `replayable = true` means the row
-- is eligible for the dashboard's "replay" action; `replayed_at` is
-- stamped on the way out so the history stays auditable.
--
-- FKs use RESTRICT: a run or step cannot be hard-deleted while there is
-- a dead-letter entry pointing at it. Operators must archive the DL row
-- first. Keeps the audit trail intact by construction.
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE dead_letters (
    id           UUID        NOT NULL PRIMARY KEY,
    run_id       UUID        NULL,
    step_id      UUID        NULL,
    reason       TEXT        NOT NULL,
    last_error   JSONB       NULL,
    attempts     INTEGER     NOT NULL DEFAULT 0,
    replayable   BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    replayed_at  TIMESTAMPTZ NULL,

    CONSTRAINT dead_letters_run_fk
        FOREIGN KEY (run_id)  REFERENCES runs  (id) ON DELETE RESTRICT,
    CONSTRAINT dead_letters_step_fk
        FOREIGN KEY (step_id) REFERENCES steps (id) ON DELETE RESTRICT,

    CONSTRAINT dead_letters_reason_not_empty      CHECK (char_length(reason) > 0),
    CONSTRAINT dead_letters_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT dead_letters_linked CHECK (
        run_id IS NOT NULL OR step_id IS NOT NULL
    )
);

CREATE INDEX dead_letters_run_id_idx  ON dead_letters (run_id)  WHERE run_id  IS NOT NULL;
CREATE INDEX dead_letters_step_id_idx ON dead_letters (step_id) WHERE step_id IS NOT NULL;

-- Dashboard "open dead letters" feed.
CREATE INDEX dead_letters_replayable_idx
    ON dead_letters (created_at DESC)
    WHERE replayable = TRUE AND replayed_at IS NULL;

COMMENT ON TABLE  dead_letters            IS 'Retries-exhausted runs/steps. Replayable from the dashboard.';
COMMENT ON COLUMN dead_letters.reason     IS 'Short machine-readable category (retry_exhausted, timeout, manual_fail, …).';
COMMENT ON COLUMN dead_letters.last_error IS 'Structured error payload (message, stack, metadata).';
COMMENT ON COLUMN dead_letters.replayable IS 'When false, operators have explicitly archived the entry.';
