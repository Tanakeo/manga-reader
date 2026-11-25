
CREATE TABLE title_reading_state (
    title_id uuid PRIMARY KEY REFERENCES titles(id) ON DELETE CASCADE,
    chapter_id varchar(200),
    language varchar(32),
    page_index integer,
    revision bigint NOT NULL DEFAULT 0,
    active_session uuid,
    last_sequence bigint NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((chapter_id IS NULL AND language IS NULL AND page_index IS NULL)
        OR (chapter_id IS NOT NULL AND language IS NOT NULL AND page_index >= 0 AND page_index IS NOT NULL))
);

CREATE TABLE chapter_read_state (
    title_id uuid REFERENCES titles(id) ON DELETE CASCADE,
    chapter_id varchar(200),
    language varchar(32),
    last_page_index integer CHECK (last_page_index >= 0),
    page_count integer CHECK (page_count > 0),
    seen_pages integer[] NOT NULL DEFAULT '{}',
    layout_key text,
    is_read boolean NOT NULL DEFAULT false,
    completed_at timestamptz,
    last_read_at timestamptz,
    chapter_title text,
    active_seconds bigint NOT NULL DEFAULT 0,
    origin text CHECK (origin IN ('ONLINE','OFFLINE','LEGACY')),
    PRIMARY KEY (title_id, chapter_id, language)
);
CREATE INDEX chapter_read_history ON chapter_read_state (last_read_at DESC, title_id DESC, chapter_id DESC, language DESC)
    WHERE last_read_at IS NOT NULL;

CREATE TABLE reading_events (
    event_id uuid PRIMARY KEY,
    title_id uuid NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
    request_hash text NOT NULL,
    revision bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reading_events_age ON reading_events(created_at);

INSERT INTO title_reading_state(title_id, chapter_id, language, page_index, updated_at)
SELECT title_id, progress_chapter_id, progress_language, progress_page_index, updated_at
FROM library_entries WHERE progress_chapter_id IS NOT NULL;

INSERT INTO chapter_read_state(title_id, chapter_id, language, last_page_index, last_read_at, origin)
SELECT title_id, progress_chapter_id, progress_language, progress_page_index, updated_at, 'LEGACY'
FROM library_entries WHERE progress_chapter_id IS NOT NULL;
