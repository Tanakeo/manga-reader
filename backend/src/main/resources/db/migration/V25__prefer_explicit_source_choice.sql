CREATE OR REPLACE FUNCTION work_summary(work uuid, languages text[], preferred_source text DEFAULT '')
RETURNS jsonb LANGUAGE sql STABLE AS $$
WITH variants AS (
    SELECT t.*,catalog_availability(t.id,CASE WHEN cardinality(languages)=0 THEN t.supported_languages
        ELSE ARRAY(SELECT unnest(t.supported_languages) INTERSECT SELECT unnest(languages)) END) AS availability,
        jsonb_build_object('titleId',t.id,'sourceId',t.source_id,'externalTitleId',t.external_title_id,
            'sourceName',t.source_name,'supportedLanguages',t.supported_languages,'connected',t.connected,
            'title',t.title,'titleRu',t.title_ru,'titleEn',t.title_en,'author',t.author,'coverUrl',t.cover_url,
            'publicationStatus',t.publication_status,'releaseYear',t.release_year) AS base
    FROM work_source_rows t WHERE t.work_id=work
), chosen AS (
    SELECT v.* FROM variants v LEFT JOIN work_source_preferences p ON p.work_id=work
    LEFT JOIN title_reading_state r ON r.title_id=v.id AND r.chapter_id IS NOT NULL AND (cardinality(languages)=0 OR r.language=ANY(languages))
    ORDER BY v.connected DESC,
        (v.availability->>'presence'='FOUND') DESC,
        (preferred_source<>'' AND v.source_id=preferred_source) DESC,
        (p.preferred_title_id=v.id AND p.updated_at>=coalesce(
            (SELECT max(r2.updated_at) FROM title_reading_state r2 JOIN variants v2 ON v2.id=r2.title_id
             WHERE r2.chapter_id IS NOT NULL AND (cardinality(languages)=0 OR r2.language=ANY(languages))),'-infinity'::timestamptz)) DESC,
        (r.chapter_id IS NOT NULL) DESC,r.updated_at DESC NULLS LAST,
        (p.preferred_title_id=v.id) DESC,
        v.source_id,v.id LIMIT 1
), metric AS (
    SELECT m.* FROM title_metric_observations m JOIN titles t ON t.id=m.title_id
    WHERE t.work_id=work ORDER BY t.id,m.fetched_at DESC LIMIT 1
), cover AS (
    SELECT * FROM variants WHERE cover_url IS NOT NULL AND btrim(cover_url)<>''
    ORDER BY connected DESC,(id=(SELECT id FROM chosen)) DESC,source_id,id LIMIT 1
)
SELECT c.base || jsonb_build_object(
    'workId',work,'version',(SELECT version FROM works WHERE id=work),
    'selectedTitleId',c.id,'description',c.description,'sourceUrl',c.source_url,
    'license',CASE WHEN c.license_code IS NULL THEN NULL ELSE jsonb_build_object('code',c.license_code,'url',c.license_url) END,
    'coverUrl',(SELECT cover_url FROM cover),
    'coverTitle',(SELECT jsonb_build_object('sourceId',source_id,'externalTitleId',external_title_id) FROM cover),
    'chapterAvailability',c.availability,
    'sources',coalesce((SELECT jsonb_agg(base || jsonb_build_object('chapterAvailability',availability) ORDER BY source_id,id)
                       FROM variants WHERE connected),'[]'),
    'allSources',coalesce((SELECT jsonb_agg(base || jsonb_build_object('chapterAvailability',availability) ORDER BY source_id,id)
                       FROM variants),'[]'),
    'tags',coalesce((SELECT jsonb_agg(jsonb_build_object('id',st.external_tag_id,'group',st.tag_group,'names',st.names))
        FROM title_tags tt JOIN source_tags st USING(source_id,external_tag_id) WHERE tt.title_id=c.id),'[]'),
    'preference', (SELECT jsonb_build_object('titleId',preferred_title_id,'language',language,'version',version)
                   FROM work_source_preferences WHERE work_id=work),
    'metrics',coalesce((SELECT jsonb_agg(value) FROM metric m CROSS JOIN LATERAL (
        SELECT jsonb_build_object('provider',m.provider,'providerExternalId',m.provider_external_id,
          'metric',v.kind,'value',v.value,'scaleMax',v.scale,'sampleSize',v.samples,
          'fetchedAt',m.fetched_at,'stale',m.fetched_at<now()-interval '24 hours',
          'state',CASE WHEN m.fetched_at<now()-interval '24 hours' THEN 'STALE' ELSE 'AVAILABLE' END) value
        FROM (VALUES ('FOLLOWERS',m.follows::numeric,NULL::integer,NULL::bigint),
                     ('RATING_BAYESIAN',m.rating,10,m.votes),('RATING_MEAN',m.mean_rating,10,m.votes)) v(kind,value,scale,samples)
        ) values),'[]'))
FROM chosen c;
$$;
