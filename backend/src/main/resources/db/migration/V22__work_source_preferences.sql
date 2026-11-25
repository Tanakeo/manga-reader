CREATE TABLE work_source_preferences (
    work_id uuid PRIMARY KEY REFERENCES works(id),
    preferred_title_id uuid NOT NULL REFERENCES titles(id),
    language text,
    version bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);
