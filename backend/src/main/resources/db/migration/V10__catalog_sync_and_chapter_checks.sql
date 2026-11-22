ALTER TABLE titles
    ADD COLUMN discovered_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN last_seen_at timestamptz,
    ADD COLUMN last_seen_run_id uuid,
    ADD COLUMN source_latest_chapter_at timestamptz,
    ADD COLUMN search_document tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('russian', coalesce(title_ru, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(title_en, '')), 'A')
    ) STORED;
CREATE INDEX titles_search ON titles USING gin(search_document);
CREATE INDEX titles_source_name ON titles(source_id, lower(title), external_title_id);

CREATE TABLE source_catalog_state (
    source_id varchar(64) PRIMARY KEY,
    languages text[] NOT NULL,
    visibility_version bigint NOT NULL DEFAULT 0
);
CREATE TABLE catalog_sync_runs (
    id uuid PRIMARY KEY,
    source_id varchar(64) NOT NULL REFERENCES source_catalog_state,
    mode text NOT NULL CHECK (mode IN ('FULL', 'INCREMENTAL')),
    state text NOT NULL CHECK (state IN ('QUEUED','RUNNING','WAITING_RETRY','PAUSED','COMPLETED','PARTIAL','FAILED')),
    phase text NOT NULL DEFAULT 'CATALOG',
    cursor text,
    pages_committed integer NOT NULL DEFAULT 0,
    rows_seen bigint NOT NULL DEFAULT 0,
    source_total bigint,
    stop_reason text,
    terminal_evidence jsonb,
    last_fingerprint text,
    attempts integer NOT NULL DEFAULT 0,
    attempt_version bigint NOT NULL DEFAULT 0,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    error jsonb,
    started_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);
CREATE UNIQUE INDEX catalog_one_active_run ON catalog_sync_runs(source_id)
    WHERE state IN ('QUEUED','RUNNING','WAITING_RETRY','PAUSED');
CREATE TABLE catalog_sync_seen (
    run_id uuid REFERENCES catalog_sync_runs ON DELETE CASCADE,
    title_id uuid REFERENCES titles ON DELETE CASCADE,
    PRIMARY KEY(run_id, title_id)
);
CREATE TABLE title_chapter_checks (
    title_id uuid REFERENCES titles ON DELETE CASCADE,
    language varchar(32),
    policy_version text NOT NULL,
    state text NOT NULL CHECK (state IN ('UNVERIFIED','CHECKING','PARTIAL','COMPLETE','ERROR')),
    presence text NOT NULL DEFAULT 'UNKNOWN' CHECK (presence IN ('UNKNOWN','FOUND','NONE')),
    exact_count integer,
    known_count integer NOT NULL DEFAULT 0,
    checked_at timestamptz,
    last_success_at timestamptz,
    next_check_at timestamptz,
    raw_count integer NOT NULL DEFAULT 0,
    rejected_count integer NOT NULL DEFAULT 0,
    error jsonb,
    PRIMARY KEY(title_id, language),
    CHECK (exact_count IS NULL OR state = 'COMPLETE'),
    CHECK (presence <> 'NONE' OR (state = 'COMPLETE' AND exact_count = 0))
);
CREATE TABLE chapter_check_jobs (
    id uuid PRIMARY KEY,
    title_id uuid NOT NULL UNIQUE REFERENCES titles ON DELETE CASCADE,
    explicit_request boolean NOT NULL DEFAULT false,
    state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','WAITING_RETRY','DONE','ERROR')),
    attempt_version bigint NOT NULL DEFAULT 0,
    attempts integer NOT NULL DEFAULT 0,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    error jsonb
);
CREATE INDEX chapter_jobs_pending ON chapter_check_jobs(state, next_attempt_at);
CREATE TABLE source_chapters (
    title_id uuid REFERENCES titles ON DELETE CASCADE,
    source_chapter_id varchar(200),
    logical_key text NOT NULL,
    languages text[] NOT NULL,
    published_at timestamptz,
    data jsonb NOT NULL,
    PRIMARY KEY(title_id, source_chapter_id)
);
CREATE INDEX chapters_title_languages ON source_chapters(title_id);

CREATE FUNCTION catalog_availability(title_key uuid, requested_languages text[])
RETURNS jsonb LANGUAGE sql STABLE AS $$
WITH checks AS (
    SELECT l.language, c.*
    FROM unnest(requested_languages) l(language)
    LEFT JOIN title_chapter_checks c ON c.title_id=title_key AND c.language=l.language
), counts AS (
    SELECT count(DISTINCT logical_key)::integer AS logical_count, count(*)::integer AS edition_count
    FROM source_chapters
    WHERE title_id=title_key AND languages && requested_languages
), summary AS (
    SELECT coalesce(bool_and(coalesce(state='COMPLETE' AND policy_version='catalog-v1', false)), false) AS complete,
           bool_or(state='ERROR') AS failed, bool_or(state='CHECKING') AS checking,
           bool_or(state='PARTIAL') AS partial, min(checked_at) AS checked_at,
           min(last_success_at) AS last_success_at, min(next_check_at) AS next_check_at
    FROM checks
)
SELECT jsonb_build_object(
    'state', CASE WHEN complete THEN 'COMPLETE' WHEN failed THEN 'ERROR'
                  WHEN checking THEN 'CHECKING' WHEN partial THEN 'PARTIAL' ELSE 'UNVERIFIED' END,
    'presence', CASE WHEN logical_count>0 THEN 'FOUND' WHEN complete THEN 'NONE' ELSE 'UNKNOWN' END,
    'languages', requested_languages,
    'chapterCount', CASE WHEN complete THEN logical_count END,
    'editionCount', CASE WHEN complete THEN edition_count END,
    'knownChapterCount', logical_count,
    'checkedAt', checked_at, 'lastSuccessAt', last_success_at, 'nextCheckAt', next_check_at,
    'error', (SELECT error FROM checks WHERE error IS NOT NULL LIMIT 1)
) FROM summary CROSS JOIN counts;
$$;
