ALTER TABLE chapter_downloads DROP CONSTRAINT chapter_downloads_status_check;
ALTER TABLE chapter_downloads ADD CHECK(status IN ('QUEUED','DOWNLOADING','PAUSED','READY','FAILED','DELETING'));
ALTER TABLE chapter_downloads ADD COLUMN manifest_key text, ADD COLUMN priority integer NOT NULL DEFAULT 0;
CREATE TABLE chapter_bookmarks (
    title_id uuid REFERENCES titles(id) ON DELETE CASCADE,
    chapter_id varchar(200),
    language varchar(32),
    page_index integer CHECK(page_index >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(title_id,chapter_id,language)
);
