-- ─────────────────────────────────────────────────────────────────────
-- V4 — events
-- ─────────────────────────────────────────────────────────────────────
-- Append-only log of incoming webhook deliveries and internal signals.
-- EPIC 4 matches rows here against steps.wait_event_type + wait_match
-- to resume paused workflows.
--
-- `source` distinguishes internal signals ('internal') from per-provider
-- webhook deliveries ('webhook:stripe', 'webhook:github', …). The
-- partial unique index on (source, external_id) dedupes providers that
-- retry deliveries with the same external event id.
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE events (
    id           UUID        NOT NULL PRIMARY KEY,
    type         TEXT        NOT NULL,
    source       TEXT        NOT NULL DEFAULT 'internal',
    external_id  TEXT        NULL,
    payload      JSONB       NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT events_type_not_empty   CHECK (char_length(type) > 0),
    CONSTRAINT events_source_not_empty CHECK (char_length(source) > 0)
);

-- Webhook dedup (Stripe + GitHub retry with the same upstream id).
CREATE UNIQUE INDEX events_source_external_uniq
    ON events (source, external_id)
    WHERE external_id IS NOT NULL;

-- Look up recent events by type (event matcher + dashboard feed).
CREATE INDEX events_type_received_at_idx ON events (type, received_at DESC);

COMMENT ON TABLE  events             IS 'Append-only webhook deliveries + internal signals. Matched against waiting steps.';
COMMENT ON COLUMN events.source      IS 'Origin of the event: internal or webhook:<provider>.';
COMMENT ON COLUMN events.external_id IS 'Upstream event id (e.g. Stripe evt_*); nullable for internal signals.';
