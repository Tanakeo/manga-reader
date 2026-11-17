ALTER TABLE titles
    ADD COLUMN title_ru TEXT,
    ADD COLUMN title_en TEXT,
    ADD COLUMN publication_status VARCHAR(16),
    ADD COLUMN release_year INTEGER;

UPDATE titles
SET title_ru = 'Перчинка и Морквик', title_en = 'Pepper&Carrot', release_year = 2014
WHERE source_id = 'peppercarrot' AND external_title_id = 'pepper-and-carrot';
