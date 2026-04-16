-- ─────────────────────────────────────────────────────────────────────
-- V3 — steps
-- ─────────────────────────────────────────────────────────────────────
-- Every primitive in the SDK (`step.run`, `step.sleep`, `step.waitForEvent`,
-- `step.fan`, `step.invoke`) materializes as a row here. The row is the
-- durable memoization unit: once a step is `completed`, the SDK never
-- re-invokes the user's handler on replay — it reads the stored `output`
-- and moves on.
--
-- (run_id, name) is unique so deterministic replay maps SDK calls back to
-- their stored results. Fan-out children encode position via fan_group_id
-- + fan_index and therefore use suffixed names (e.g. "step[0]") so this
-- uniqueness rule still holds.
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE steps (
    id                 UUID        NOT NULL PRIMARY KEY,
    run_id             UUID        NOT NULL,
    name               TEXT        NOT NULL,
    status             TEXT        NOT NULL,
    attempt            INTEGER     NOT NULL DEFAULT 0,

    input              JSONB       NOT NULL DEFAULT '{}'::jsonb,
    output             JSONB       NULL,
    error              JSONB       NULL,

    wait_event_type    TEXT        NULL,
    wait_match         JSONB       NULL,

    scheduled_at       TIMESTAMPTZ NULL,
    expires_at         TIMESTAMPTZ NULL,
    started_at         TIMESTAMPTZ NULL,
    completed_at       TIMESTAMPTZ NULL,

    fan_group_id       UUID        NULL,
    fan_index          INTEGER     NULL,
    fan_parent_step_id UUID        NULL,

    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT steps_run_fk
        FOREIGN KEY (run_id) REFERENCES runs (id) ON DELETE CASCADE,
    CONSTRAINT steps_fan_parent_fk
        FOREIGN KEY (fan_parent_step_id) REFERENCES steps (id) ON DELETE CASCADE,

    CONSTRAINT steps_name_not_empty     CHECK (char_length(name) > 0),
    CONSTRAINT steps_attempt_non_negative CHECK (attempt >= 0),
    CONSTRAINT steps_status_values CHECK (
        status IN (
            'pending', 'running', 'completed', 'failed',
            'waiting', 'sleeping', 'dead_lettered'
        )
    ),
    CONSTRAINT steps_fan_fields_paired CHECK (
        (fan_group_id IS NULL AND fan_index IS NULL)
            OR (fan_group_id IS NOT NULL AND fan_index IS NOT NULL AND fan_index >= 0)
    ),
    CONSTRAINT steps_wait_fields_paired CHECK (
        (wait_event_type IS NULL AND wait_match IS NULL)
            OR (wait_event_type IS NOT NULL AND wait_match IS NOT NULL)
    ),
    CONSTRAINT steps_run_name_uniq UNIQUE (run_id, name)
);

-- List-all-steps-for-a-run (dashboard, replay).
CREATE INDEX steps_run_id_idx ON steps (run_id);

-- Find matching waiting steps when an event lands. The full containment
-- index on wait_match goes in STORY 2.2; this partial BTREE already
-- narrows the candidate set to rows that actually have a wait configured.
CREATE INDEX steps_wait_event_type_idx
    ON steps (wait_event_type)
    WHERE wait_event_type IS NOT NULL AND status = 'waiting';

-- Fan-in sibling lookups; upgraded to a tighter composite in STORY 2.2.
CREATE INDEX steps_fan_group_id_idx
    ON steps (fan_group_id)
    WHERE fan_group_id IS NOT NULL;

COMMENT ON TABLE  steps                     IS 'Durable step results. Memoization unit for workflow replay.';
COMMENT ON COLUMN steps.wait_event_type     IS 'When status=waiting, the event type the step is blocked on.';
COMMENT ON COLUMN steps.wait_match          IS 'JSONB shape used with @> to match incoming events to this step.';
COMMENT ON COLUMN steps.fan_group_id        IS 'Shared by every child of a step.fan(...) call.';
COMMENT ON COLUMN steps.fan_index           IS 'Position within the fan group; preserves result ordering.';
COMMENT ON COLUMN steps.fan_parent_step_id  IS 'The fan step that spawned this child.';
