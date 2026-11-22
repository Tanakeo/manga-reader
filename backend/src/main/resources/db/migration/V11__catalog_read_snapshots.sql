CREATE TABLE catalog_read_snapshots (
    id uuid PRIMARY KEY,
    query_hash text NOT NULL,
    source_versions jsonb NOT NULL,
    connected_sources text[] NOT NULL,
    total integer NOT NULL DEFAULT 0,
    captured_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT now() + interval '15 minutes'
);
CREATE TABLE catalog_read_snapshot_items (
    snapshot_id uuid REFERENCES catalog_read_snapshots ON DELETE CASCADE,
    position integer,
    data jsonb NOT NULL,
    PRIMARY KEY(snapshot_id, position)
);
CREATE INDEX catalog_snapshots_expiry ON catalog_read_snapshots(expires_at);
