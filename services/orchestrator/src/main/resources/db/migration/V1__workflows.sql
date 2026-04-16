-- ─────────────────────────────────────────────────────────────────────
-- V1 — workflows
-- ─────────────────────────────────────────────────────────────────────
-- Stores workflow definitions registered by the SDK. Append-only:
-- re-registration with a changed definition creates a new row with
-- version = N+1. Running runs always reference a specific workflow id
-- (and therefore a specific version) so in-flight executions never
-- observe a definition change mid-run.
--
-- Table is unqualified so the migration runs against whatever schema
-- Flyway's search_path is pointing at (production: `pgpilot`).
-- ─────────────────────────────────────────────────────────────────────

CREATE TABLE workflows (
    id         UUID        NOT NULL PRIMARY KEY,
    name       TEXT        NOT NULL,
    version    INTEGER     NOT NULL,
    definition JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT workflows_name_not_empty    CHECK (char_length(name) > 0),
    CONSTRAINT workflows_version_positive  CHECK (version > 0),
    CONSTRAINT workflows_name_version_uniq UNIQUE (name, version)
);

-- "Latest by name" lookup used by the SDK registration path.
CREATE INDEX workflows_name_version_desc_idx
    ON workflows (name, version DESC);

COMMENT ON TABLE  workflows             IS 'Registered workflow definitions. Append-only, versioned per name.';
COMMENT ON COLUMN workflows.definition  IS 'Serialized workflow metadata (step names, schema hints).';
COMMENT ON COLUMN workflows.version     IS 'Monotonic per name; auto-incremented by the repository.';
