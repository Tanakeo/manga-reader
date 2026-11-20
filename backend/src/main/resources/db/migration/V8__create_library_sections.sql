CREATE TABLE library_sections (
    id UUID PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    normalized_name TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT current_timestamp
);

CREATE TABLE library_section_entries (
    section_id UUID NOT NULL REFERENCES library_sections(id) ON DELETE CASCADE,
    entry_id UUID NOT NULL REFERENCES library_entries(id) ON DELETE CASCADE,
    PRIMARY KEY (section_id, entry_id)
);

CREATE INDEX library_section_entries_entry_idx ON library_section_entries(entry_id);
