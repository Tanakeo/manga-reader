CREATE TABLE library_transfers (
    origin_entry_id uuid NOT NULL,
    target_title_id uuid NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
    target_entry_id uuid NOT NULL REFERENCES library_entries(id) ON DELETE CASCADE,
    PRIMARY KEY(origin_entry_id,target_title_id)
);
