CREATE TABLE chapter_downloads (
    id uuid PRIMARY KEY,
    source_id varchar(64) NOT NULL,
    external_title_id varchar(200) NOT NULL,
    chapter_id varchar(200) NOT NULL,
    language varchar(32) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('QUEUED', 'DOWNLOADING', 'READY', 'FAILED')),
    pages_done integer NOT NULL DEFAULT 0,
    total_pages integer,
    chapter_number numeric,
    chapter_title text,
    title_data jsonb NOT NULL,
    chapter_data jsonb,
    error text,
    UNIQUE (source_id, external_title_id, chapter_id, language)
);

CREATE INDEX chapter_downloads_queue ON chapter_downloads(chapter_number, id) WHERE status = 'QUEUED';
