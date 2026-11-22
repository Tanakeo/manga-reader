CREATE TABLE reader_manifests (
    source_id varchar(64),
    external_title_id varchar(200),
    chapter_id varchar(200),
    language varchar(32),
    version text NOT NULL,
    data jsonb NOT NULL,
    fetched_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    image_check_state text NOT NULL DEFAULT 'UNVERIFIED',
    last_error jsonb,
    PRIMARY KEY(source_id, external_title_id, chapter_id, language)
);
CREATE INDEX reader_manifests_expiry ON reader_manifests(expires_at);
