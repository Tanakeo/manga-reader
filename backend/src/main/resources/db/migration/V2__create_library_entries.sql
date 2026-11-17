CREATE TABLE library_entries (
    id UUID PRIMARY KEY,
    title_id UUID NOT NULL REFERENCES titles(id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PLANNED', 'READING', 'COMPLETED')),
    note VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    progress_chapter_id VARCHAR(200),
    progress_language VARCHAR(32),
    progress_page_index INTEGER,
    CONSTRAINT library_entries_title_key UNIQUE (title_id),
    CONSTRAINT library_entries_progress_valid CHECK (
        (progress_chapter_id IS NULL AND progress_language IS NULL AND progress_page_index IS NULL)
        OR (progress_chapter_id IS NOT NULL AND progress_language IS NOT NULL
            AND progress_page_index IS NOT NULL AND progress_page_index >= 0)
    )
);
