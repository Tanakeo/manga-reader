ALTER TABLE titles
    ADD COLUMN tags_synced_at TIMESTAMPTZ,
    ADD COLUMN tags_source_version INTEGER,
    ADD UNIQUE (id, source_id);

CREATE TABLE source_tags (
    source_id VARCHAR(64),
    external_tag_id VARCHAR(200),
    tag_group TEXT NOT NULL,
    names JSONB NOT NULL CHECK (jsonb_typeof(names) = 'object'),
    source_version INTEGER,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (source_id, external_tag_id)
);

CREATE TABLE title_tags (
    title_id UUID,
    source_id VARCHAR(64),
    external_tag_id VARCHAR(200),
    PRIMARY KEY (title_id, source_id, external_tag_id),
    FOREIGN KEY (title_id, source_id) REFERENCES titles(id, source_id) ON DELETE CASCADE,
    FOREIGN KEY (source_id, external_tag_id) REFERENCES source_tags(source_id, external_tag_id)
);

CREATE INDEX title_tags_by_tag ON title_tags (source_id, external_tag_id, title_id);
CREATE INDEX unsynced_title_tags ON titles (source_id, external_title_id) WHERE tags_synced_at IS NULL;
