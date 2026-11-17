ALTER TABLE chapter_downloads DROP CONSTRAINT chapter_downloads_status_check;
ALTER TABLE chapter_downloads
    ADD CHECK (status IN ('QUEUED', 'DOWNLOADING', 'READY', 'FAILED', 'DELETING')),
    ADD COLUMN title_id UUID REFERENCES titles(id) ON DELETE RESTRICT,
    ADD COLUMN attempt_no INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN error_code TEXT,
    ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

INSERT INTO titles (id, source_id, external_title_id, source_name, title, source_url, author, title_ru, title_en,
                    publication_status, release_year)
SELECT DISTINCT ON (source_id, external_title_id) gen_random_uuid(), source_id, external_title_id,
       title_data->>'sourceName', title_data->>'title',
       'https://www.peppercarrot.com/en/webcomics/peppercarrot.html', title_data->>'author',
       title_data->>'titleRu', title_data->>'titleEn', title_data->>'publicationStatus',
       (title_data->>'releaseYear')::integer
FROM chapter_downloads
ORDER BY source_id, external_title_id, id
ON CONFLICT (source_id, external_title_id) DO NOTHING;

UPDATE chapter_downloads d SET title_id = t.id FROM titles t
WHERE (d.source_id, d.external_title_id) = (t.source_id, t.external_title_id);

ALTER TABLE chapter_downloads ALTER COLUMN title_id SET NOT NULL;
ALTER TABLE chapter_downloads ADD UNIQUE (title_id, chapter_id, language);
UPDATE chapter_downloads SET error_code = 'DOWNLOAD_FAILED' WHERE error IS NOT NULL;
CREATE INDEX chapter_downloads_by_state ON chapter_downloads (status, created_at, id);

CREATE TABLE download_pages (
    download_id UUID REFERENCES chapter_downloads(id) ON DELETE CASCADE,
    page_index INTEGER CHECK (page_index >= 0),
    storage_key TEXT NOT NULL UNIQUE,
    mime_type TEXT NOT NULL CHECK (mime_type IN ('image/jpeg', 'image/gif')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 CHAR(64) NOT NULL,
    PRIMARY KEY (download_id, page_index)
);
