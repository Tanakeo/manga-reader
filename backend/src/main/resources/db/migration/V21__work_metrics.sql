CREATE TABLE title_metric_observations (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title_id uuid NOT NULL REFERENCES titles(id),
    provider text NOT NULL,
    provider_external_id text NOT NULL,
    schema_version integer NOT NULL DEFAULT 1,
    follows bigint NOT NULL CHECK(follows>=0),
    rating numeric,
    mean_rating numeric,
    votes bigint NOT NULL CHECK(votes>=0),
    distribution jsonb NOT NULL,
    fetched_at timestamptz NOT NULL DEFAULT now(),
    observation_day date NOT NULL DEFAULT current_date,
    UNIQUE(provider,provider_external_id,observation_day)
);
CREATE INDEX title_metrics_latest ON title_metric_observations(title_id,fetched_at DESC);
CREATE TABLE title_metric_state (
    title_id uuid PRIMARY KEY REFERENCES titles(id) ON DELETE CASCADE,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_success_at timestamptz,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    priority integer NOT NULL DEFAULT 0,
    generation bigint NOT NULL DEFAULT 0,
    error jsonb
);
CREATE INDEX metrics_queue ON title_metric_state(next_attempt_at);
