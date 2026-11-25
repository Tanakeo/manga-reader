CREATE TABLE local_comic_chapters (
    title_id uuid REFERENCES titles(id) ON DELETE CASCADE,
    chapter_id varchar(200),
    language varchar(32) NOT NULL,
    chapter_title varchar(200) NOT NULL,
    media_types text[] NOT NULL,
    imported_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(title_id,chapter_id)
);
