INSERT INTO title_aliases(title_id,name,normalized,language,provenance)
SELECT t.id,n.name,trim(regexp_replace(lower(n.name),'[^[:alnum:]]+',' ','g')),n.language,'saved-title'
FROM titles t CROSS JOIN LATERAL (VALUES(t.title,''),(t.title_ru,'ru'),(t.title_en,'en')) n(name,language)
WHERE n.name IS NOT NULL AND btrim(n.name)<>''
ON CONFLICT(title_id,name,language) DO UPDATE SET normalized=excluded.normalized;
