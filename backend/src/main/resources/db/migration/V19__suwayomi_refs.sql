CREATE TABLE suwayomi_title_refs (
    source_id varchar(64),
    external_id varchar(200),
    source_locator text NOT NULL,
    label text NOT NULL,
    service_id integer NOT NULL,
    PRIMARY KEY(source_id,external_id)
);
