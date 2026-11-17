CREATE TABLE titles (
    id UUID PRIMARY KEY,
    source_id VARCHAR(64) NOT NULL,
    external_title_id VARCHAR(200) NOT NULL,
    source_name TEXT NOT NULL,
    title TEXT NOT NULL,
    source_url TEXT NOT NULL,
    author TEXT,
    description TEXT,
    cover_url TEXT,
    license_code TEXT,
    license_url TEXT,
    CONSTRAINT titles_source_external_key UNIQUE (source_id, external_title_id),
    CONSTRAINT titles_license_pair CHECK ((license_code IS NULL) = (license_url IS NULL))
);
