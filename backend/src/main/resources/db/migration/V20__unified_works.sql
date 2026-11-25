CREATE TABLE works (
    id uuid PRIMARY KEY,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE titles ADD COLUMN work_id uuid;
INSERT INTO works(id) SELECT id FROM titles ON CONFLICT DO NOTHING;
UPDATE titles SET work_id=id WHERE work_id IS NULL;
ALTER TABLE titles ALTER COLUMN work_id SET NOT NULL;
ALTER TABLE titles ADD CONSTRAINT titles_work_fk FOREIGN KEY(work_id) REFERENCES works(id);
CREATE INDEX titles_work ON titles(work_id);

CREATE FUNCTION assign_title_work() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.work_id IS NULL THEN
        NEW.work_id := NEW.id;
        INSERT INTO works(id) VALUES(NEW.id) ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER titles_assign_work BEFORE INSERT ON titles FOR EACH ROW EXECUTE FUNCTION assign_title_work();

CREATE TABLE title_aliases (
    title_id uuid REFERENCES titles(id) ON DELETE CASCADE,
    name text NOT NULL,
    normalized text NOT NULL,
    language text NOT NULL DEFAULT '',
    provenance text NOT NULL,
    PRIMARY KEY(title_id,name,language)
);
CREATE INDEX title_alias_names ON title_aliases(normalized);
INSERT INTO title_aliases(title_id,name,normalized,language,provenance)
SELECT id,title,lower(title),'','saved-title' FROM titles;
CREATE TABLE title_external_identifiers (
    title_id uuid REFERENCES titles(id) ON DELETE CASCADE,
    namespace text,
    external_id text,
    fetched_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(title_id,namespace,external_id)
);
CREATE INDEX external_identifier_lookup ON title_external_identifiers(namespace,external_id);
ALTER TABLE titles ADD COLUMN identity_metadata jsonb;
CREATE TABLE work_match_candidates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    left_title uuid NOT NULL REFERENCES titles(id),
    right_title uuid NOT NULL REFERENCES titles(id),
    state text NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','CONFIRM','REJECT')),
    evidence jsonb NOT NULL DEFAULT '{}',
    version bigint NOT NULL DEFAULT 0,
    reason text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(left_title,right_title),
    CHECK(left_title<right_title)
);
CREATE TABLE work_merge_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    from_work uuid REFERENCES works(id),
    into_work uuid REFERENCES works(id),
    membership jsonb NOT NULL,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE work_redirects (
    old_id uuid PRIMARY KEY REFERENCES works(id),
    target_id uuid NOT NULL REFERENCES works(id),
    CHECK(old_id<>target_id)
);
CREATE TABLE work_catalog_version (id integer PRIMARY KEY CHECK(id=1), version bigint NOT NULL DEFAULT 0);
INSERT INTO work_catalog_version(id) VALUES(1);
