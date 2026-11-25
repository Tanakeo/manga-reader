CREATE TABLE library_update_baselines (
    title_id uuid PRIMARY KEY REFERENCES titles(id) ON DELETE CASCADE,
    policy_version text NOT NULL
);
CREATE TABLE library_update_editions (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title_id uuid NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
    chapter_id varchar(200) NOT NULL,
    language varchar(32) NOT NULL,
    chapter_title text NOT NULL,
    published_at timestamptz,
    discovered_at timestamptz NOT NULL DEFAULT now(),
    announce boolean NOT NULL,
    UNIQUE(title_id,chapter_id,language)
);
CREATE INDEX library_updates_feed ON library_update_editions(id DESC) WHERE announce;

