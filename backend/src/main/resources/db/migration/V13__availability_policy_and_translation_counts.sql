ALTER TABLE source_catalog_state ADD COLUMN policy_version text NOT NULL DEFAULT 'catalog-v1';
CREATE OR REPLACE FUNCTION catalog_availability(title_key uuid, requested_languages text[])
RETURNS jsonb LANGUAGE sql STABLE AS $$
WITH policy AS (
    SELECT s.policy_version FROM titles t JOIN source_catalog_state s ON s.source_id=t.source_id WHERE t.id=title_key
), checks AS (
    SELECT c.* FROM unnest(requested_languages) l(language)
    LEFT JOIN title_chapter_checks c ON c.title_id=title_key AND c.language=l.language
), counts AS (
    SELECT count(DISTINCT logical_key)::integer AS logical_count,
        coalesce(sum(cardinality(ARRAY(SELECT unnest(languages)
            INTERSECT SELECT unnest(requested_languages)))),0)::integer AS edition_count
    FROM source_chapters WHERE title_id=title_key AND languages && requested_languages
), summary AS (
    SELECT coalesce(bool_and(coalesce(state='COMPLETE' AND policy_version=(SELECT policy_version FROM policy), false)),false) AS complete,
        bool_or(state='ERROR') AS failed,bool_or(state='CHECKING') AS checking,bool_or(state='PARTIAL') AS partial,
        min(checked_at) AS checked_at,min(last_success_at) AS last_success_at,min(next_check_at) AS next_check_at
    FROM checks
)
SELECT jsonb_build_object(
    'state',CASE WHEN complete THEN 'COMPLETE' WHEN failed THEN 'ERROR' WHEN checking THEN 'CHECKING'
                 WHEN partial THEN 'PARTIAL' ELSE 'UNVERIFIED' END,
    'presence',CASE WHEN logical_count>0 THEN 'FOUND' WHEN complete THEN 'NONE' ELSE 'UNKNOWN' END,
    'languages',requested_languages,'chapterCount',CASE WHEN complete THEN logical_count END,
    'editionCount',CASE WHEN complete THEN edition_count END,'knownChapterCount',logical_count,
    'checkedAt',checked_at,'lastSuccessAt',last_success_at,'nextCheckAt',next_check_at,
    'error',(SELECT error FROM checks WHERE error IS NOT NULL LIMIT 1))
FROM summary CROSS JOIN counts;
$$;
