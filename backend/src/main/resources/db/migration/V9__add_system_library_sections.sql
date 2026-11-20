ALTER TABLE library_sections
    ADD COLUMN system_key TEXT UNIQUE
        CHECK (system_key IN ('planned', 'reading', 'completed', 'downloads'));

INSERT INTO library_sections (id, name, normalized_name, system_key) VALUES
    ('b7311428-7138-4c13-9ace-000000000001', 'В планах', 'в планах', 'planned'),
    ('b7311428-7138-4c13-9ace-000000000002', 'Читаю', 'читаю', 'reading'),
    ('b7311428-7138-4c13-9ace-000000000003', 'Прочитано', 'прочитано', 'completed'),
    ('b7311428-7138-4c13-9ace-000000000004', 'Скачанное', 'скачанное', 'downloads');
