package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.source.SourceConnectionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkService {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SourceConnectionService connections;
    private final LocalCatalogService catalog;
    private final com.github.benmanes.caffeine.cache.Cache<String,JsonNode> coverageCache=
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(32)
                    .expireAfterWrite(java.time.Duration.ofSeconds(30)).build();

    WorkService(JdbcClient jdbc, JsonMapper json, SourceConnectionService connections, LocalCatalogService catalog) {
        this.jdbc=jdbc; this.json=json; this.connections=connections; this.catalog=catalog;
    }

    public UUID resolve(UUID work) {
        for (int i=0;i<16;i++) {
            var next=jdbc.sql("SELECT target_id FROM work_redirects WHERE old_id=:id").param("id",work)
                    .query(UUID.class).optional();
            if (next.isEmpty()) return work;
            work=next.get();
        }
        throw ApiException.problem(HttpStatus.CONFLICT,"WORK_VERSION_CONFLICT","Обновите страницу произведения.");
    }

    public UUID forTitle(UUID title) {
        return jdbc.sql("SELECT work_id FROM titles WHERE id=:id").param("id",title).query(UUID.class).single();
    }

    public JsonNode summary(UUID id,List<String> languages,String source) {
        return jdbc.sql("""
                SELECT (work_summary(:id,CAST(:languages AS text[]),:source)||jsonb_build_object('latestChapterAt',
                    (SELECT max(c.published_at) FROM source_chapters c JOIN work_source_rows t ON t.id=c.title_id
                     WHERE t.work_id=:id AND t.connected AND c.published_at<=now()
                     AND (cardinality(CAST(:languages AS text[]))=0 OR c.languages && CAST(:languages AS text[])))))::text
                """)
                .param("id",resolve(id)).param("languages",CatalogSyncService.array(languages))
                .param("source",source==null ? "" : source).query(String.class).optional().map(json::readTree)
                .orElseThrow(() -> ApiException.problem(HttpStatus.NOT_FOUND,"WORK_NOT_FOUND","Произведение не найдено."));
    }

    public JsonNode detail(UUID id,List<String> languages) {
        var result=(tools.jackson.databind.node.ObjectNode) summary(id,languages,"");
        UUID canonical=UUID.fromString(result.path("workId").asText());
        result.set("libraryEntries",json.readTree(jdbc.sql("""
                SELECT coalesce(jsonb_agg(to_jsonb(e)),'[]')::text FROM library_entries e
                JOIN titles t ON t.id=e.title_id WHERE t.work_id=:work
                """).param("work",canonical).query(String.class).single()));
        result.set("relatedWorks",json.readTree(jdbc.sql("""
                SELECT coalesce(jsonb_agg(DISTINCT t.work_id),'[]')::text FROM titles t
                WHERE t.work_id<>:work AND EXISTS(SELECT 1 FROM work_merge_events e
                    WHERE (e.from_work=:work AND e.into_work=t.work_id) OR (e.into_work=:work AND e.from_work=t.work_id))
                """).param("work",canonical).query(String.class).single()));
        return result;
    }

    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public JsonNode list(String source,String query,List<String> tags,String sort,List<String> languages,
                         int limit,String cursor,boolean readable) {
        if (limit<1 || limit>100 || languages.stream().anyMatch(l -> !List.of("ru","en").contains(l)))
            throw CatalogSyncService.invalid("limit","Проверьте размер страницы и язык глав.");
        var connected=connections.connectedAdapters().stream().map(adapter -> adapter.id()).sorted().toList();
        if (source!=null && !source.isEmpty()) connections.requireConnectedAdapter(source);
        String version=jdbc.sql("SELECT version::text FROM work_catalog_version WHERE id=1").query(String.class).single();
        String signature=CatalogSyncService.hash(json.writeValueAsString(List.of(
                "works",source==null?"":source,query,tags,sort,languages,limit,readable)));
        String visibility=jdbc.sql("SELECT coalesce(jsonb_object_agg(source_id,visibility_version),'{}')::text FROM source_catalog_state")
                .query(String.class).single();
        String versions=json.writeValueAsString(Map.of("grouping",version,"sources",visibility));
        UUID snapshot;
        int offset=0;
        if (cursor==null || cursor.isBlank()) {
            snapshot=UUID.randomUUID();
            jdbc.sql("DELETE FROM catalog_read_snapshots WHERE expires_at<now()").update();
            jdbc.sql("""
                    INSERT INTO catalog_read_snapshots(id,query_hash,source_versions,connected_sources)
                    VALUES(:id,:hash,CAST(:versions AS jsonb),CAST(:sources AS text[]))
                    """).param("id",snapshot).param("hash",signature).param("versions",versions)
                    .param("sources",CatalogSyncService.array(connected)).update();
            String order=switch(sort) {
                case "popular" -> "followers DESC NULLS LAST";
                case "rating" -> "rating DESC NULLS LAST,votes DESC NULLS LAST";
                case "genre" -> "followers DESC NULLS LAST,name";
                case "latest_chapter_desc" -> "latest DESC NULLS LAST";
                case "year_desc" -> "year DESC NULLS LAST";
                case "year_asc" -> "year ASC NULLS LAST";
                default -> query.isBlank()?"name":"rank DESC,name";
            };
            String tagFilter=tags.isEmpty()?"":"""
                    AND (SELECT count(DISTINCT external_tag_id) FROM title_tags tt
                         WHERE tt.title_id=t.id AND external_tag_id IN (:tags))=:tagCount
                    """;
            String sql="""
                    INSERT INTO catalog_read_snapshot_items(snapshot_id,position,data)
                    WITH eligible AS (
                        SELECT t.* FROM work_source_rows t
                        CROSS JOIN LATERAL (SELECT CASE
                          WHEN cardinality(CAST(:languages AS text[]))=0 THEN supported_languages
                          ELSE ARRAY(SELECT unnest(supported_languages) INTERSECT SELECT unnest(CAST(:languages AS text[]))) END languages) av
                        WHERE t.source_id=ANY(CAST(:sources AS text[])) AND (:source='' OR t.source_id=:source)
                        AND cardinality(av.languages)>0
                        AND (EXISTS(SELECT 1 FROM source_chapters c WHERE c.title_id=t.id AND c.languages && av.languages)
                          OR (NOT :readable AND EXISTS(SELECT 1 FROM unnest(av.languages) l(language)
                            LEFT JOIN title_chapter_checks c ON c.title_id=t.id AND c.language=l.language
                            JOIN source_catalog_state s ON s.source_id=t.source_id
                            WHERE c.state IS DISTINCT FROM 'COMPLETE' OR c.policy_version IS DISTINCT FROM s.policy_version)))
                        AND (:q='' OR EXISTS(SELECT 1 FROM titles other JOIN title_aliases a ON a.title_id=other.id
                            WHERE other.work_id=t.work_id AND lower(a.name) LIKE :substring)
                            OR t.search_document@@websearch_to_tsquery('simple',:q)
                            OR t.search_document@@websearch_to_tsquery('russian',:q))
                    """+tagFilter+"""
                    ), matching AS (
                        SELECT work_id,min(lower(title)) AS name,max(release_year) AS year,array_agg(id) AS title_ids,
                            max(CASE WHEN lower(title)=lower(:q) THEN 10 ELSE
                                ts_rank(search_document,websearch_to_tsquery('simple',:q))+
                                ts_rank(search_document,websearch_to_tsquery('russian',:q)) END) AS rank
                        FROM eligible e
                        GROUP BY work_id
                    ), ranked AS (
                        SELECT m.*,metric.follows AS followers,metric.rating,metric.votes,
                            (SELECT max(c.published_at) FROM source_chapters c
                             WHERE c.title_id=ANY(m.title_ids) AND c.published_at<=now()
                             AND (cardinality(CAST(:languages AS text[]))=0 OR c.languages && CAST(:languages AS text[]))) latest
                        FROM matching m LEFT JOIN LATERAL (
                            SELECT o.* FROM title_metric_observations o JOIN titles t ON t.id=o.title_id
                            WHERE t.work_id=m.work_id ORDER BY t.id,o.fetched_at DESC LIMIT 1
                        ) metric ON true
                    )
                    SELECT :snapshot,(row_number() OVER(ORDER BY
                    """+order+",work_id)-1)::integer,jsonb_build_object('workId',work_id) FROM ranked WHERE true "
                    +(sort.equals("popular")?" AND followers IS NOT NULL":"")
                    +(sort.equals("rating")?" AND rating IS NOT NULL AND votes>=100":"")
                    +(readable && sort.equals("latest_chapter_desc")?" AND latest IS NOT NULL":"");
            var statement=jdbc.sql(sql).param("snapshot",snapshot).param("sources",CatalogSyncService.array(connected))
                    .param("languages",CatalogSyncService.array(languages)).param("source",source==null?"":source)
                    .param("q",query).param("substring","%"+query.toLowerCase(java.util.Locale.ROOT)
                            .replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%")
                    .param("readable",readable);
            if (!tags.isEmpty()) statement.param("tags",tags).param("tagCount",tags.size());
            int total=statement.update();
            jdbc.sql("UPDATE catalog_read_snapshots SET total=:total WHERE id=:id")
                    .param("total",total).param("id",snapshot).update();
        } else {
            try {
                String[] parts=cursor.split(":",-1);
                if(parts.length!=2) throw new IllegalArgumentException();
                snapshot=UUID.fromString(parts[0]); offset=Integer.parseInt(parts[1]);
                if(offset<0) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) { throw CatalogSyncService.invalid("cursor","Неверный курсор."); }
        }
        var state=jdbc.sql("SELECT to_jsonb(s)::text FROM catalog_read_snapshots s WHERE id=:id")
                .param("id",snapshot).query(String.class).optional().map(json::readTree)
                .orElseThrow(() -> ApiException.problem(HttpStatus.GONE,"CATALOG_CURSOR_EXPIRED","Обновите список."));
        if (!state.path("query_hash").asText().equals(signature)) throw CatalogSyncService.invalid("cursor","Курсор другого запроса.");
        if (java.time.OffsetDateTime.parse(state.path("expires_at").asText()).toInstant().isBefore(Instant.now()))
            throw ApiException.problem(HttpStatus.GONE,"CATALOG_CURSOR_EXPIRED","Обновите список.");
        if (!state.path("source_versions").equals(json.readTree(versions))
                || !state.path("connected_sources").equals(json.valueToTree(connected)))
            throw ApiException.problem(HttpStatus.CONFLICT,"CATALOG_SNAPSHOT_STALE","Каталог обновился. Обновите список.");
        var rows=jdbc.sql("""
                SELECT (data->>'workId')::uuid FROM catalog_read_snapshot_items WHERE snapshot_id=:id AND position>=:offset
                ORDER BY position LIMIT :limit
                """).param("id",snapshot).param("offset",offset).param("limit",limit).query(UUID.class).list();
        var items=rows.stream().map(id -> summary(id,languages,source)).toList();
        int total=state.path("total").asInt();
        var response=json.createObjectNode(); response.set("items",json.valueToTree(items));
        boolean more=offset+rows.size()<total;
        response.set("page",json.valueToTree(Map.of("total",total,"limit",limit,"hasMore",more,
                "snapshotId",snapshot.toString(),"capturedAt",state.path("captured_at").asText(),
                "expiresAt",state.path("expires_at").asText())));
        var page=(tools.jackson.databind.node.ObjectNode)response.path("page");
        if(more) page.put("nextCursor",snapshot+":"+(offset+rows.size())); else page.putNull("nextCursor");
        var coverage=connected.stream().map(id -> coverageCache.get(id+languages,
                key -> catalog.coverage(id,languages))).toList();
        response.set("sourceStates",json.valueToTree(coverage));
        response.put("partial",coverage.stream().anyMatch(c -> !"COMPLETE".equals(c.path("metadataState").asText())
                || c.path("verifiedTitleCount").asLong()<c.path("collectedTitleCount").asLong()));
        return response;
    }

    @Transactional
    public JsonNode prefer(UUID work,UUID title,String language,long expectedVersion) {
        work=resolve(work);
        jdbc.sql("SELECT id FROM works WHERE id=:id FOR UPDATE").param("id",work).query(UUID.class).single();
        boolean member=jdbc.sql("SELECT EXISTS(SELECT 1 FROM titles WHERE id=:title AND work_id=:work)")
                .param("title",title).param("work",work).query(Boolean.class).single();
        if(!member) throw ApiException.problem(HttpStatus.UNPROCESSABLE_CONTENT,"WORK_SOURCE_MISMATCH","Источник другого произведения.");
        String source=jdbc.sql("SELECT source_id FROM titles WHERE id=:id").param("id",title).query(String.class).single();
        var adapter=connections.requireConnectedAdapter(source);
        if(language!=null && !adapter.supportedLanguages().contains(language)) throw ApiException.unavailableLanguage(adapter.supportedLanguages());
        long version=jdbc.sql("SELECT version FROM work_source_preferences WHERE work_id=:id")
                .param("id",work).query(Long.class).optional().orElse(0L);
        if(version!=expectedVersion) throw ApiException.problem(HttpStatus.CONFLICT,"WORK_VERSION_CONFLICT","Предпочтение изменилось. Обновите страницу.");
        jdbc.sql("""
                INSERT INTO work_source_preferences(work_id,preferred_title_id,language) VALUES(:work,:title,:language)
                ON CONFLICT(work_id) DO UPDATE SET preferred_title_id=excluded.preferred_title_id,
                    language=excluded.language,version=work_source_preferences.version+1,updated_at=now()
                """).param("work",work).param("title",title).param("language",language).update();
        return detail(work,List.of());
    }
}
