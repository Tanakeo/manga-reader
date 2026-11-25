package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.source.MangaSource;
import io.github.mangareader.source.SourceConnectionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class LocalCatalogService {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final SourceConnectionService connections;
    private final TransactionTemplate reads;

    public LocalCatalogService(JdbcClient jdbc, JsonMapper json, SourceConnectionService connections,
                               PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.connections = connections;
        this.reads = new TransactionTemplate(transactions);
        this.reads.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public JsonNode catalog(String sourceId, String query, List<String> tags, String sort,
                            List<String> languages, int limit, String cursor) {
        if (limit < 1 || limit > 100) throw CatalogSyncService.invalid("limit", "Размер страницы от 1 до 100.");
        if (languages.stream().anyMatch(language -> !List.of("ru", "en").contains(language))) {
            throw CatalogSyncService.invalid("chapterLanguage", "Доступны ru и en.");
        }
        var connected = sourceId == null ? connections.connectedAdapters()
                : List.of(connections.requireConnectedAdapter(sourceId));
        var eligible = connected.stream().filter(source -> languages.isEmpty()
                || source.supportedLanguages().stream().anyMatch(languages::contains)).toList();
        if (sourceId != null && eligible.isEmpty()) {
            throw ApiException.unavailableLanguage(connected.getFirst().supportedLanguages());
        }
        if (!tags.isEmpty()) validateTags(sourceId, tags);
        var ids = eligible.stream().map(MangaSource::id).sorted().toList();
        String queryHash = CatalogSyncService.hash(json.writeValueAsString(
                List.of(sourceId == null ? "" : sourceId, query, tags, sort, languages, limit)));
        return reads.execute(transaction -> {
            UUID snapshotId;
            int position = 0;
            if (cursor == null || cursor.isBlank()) {
                snapshotId = createSnapshot(ids, query, tags, sort, languages, queryHash);
            } else {
                try {
                    String[] parts = cursor.split(":", -1);
                    if (parts.length != 2) throw new IllegalArgumentException();
                    snapshotId = UUID.fromString(parts[0]);
                    position = Integer.parseInt(parts[1]);
                    if (position < 0) throw new IllegalArgumentException();
                } catch (IllegalArgumentException error) { throw CatalogSyncService.invalid("cursor", "Неверный курсор."); }
            }
            JsonNode snapshot = snapshot(snapshotId);
            if (!snapshot.path("queryHash").asText().equals(queryHash)) {
                throw CatalogSyncService.invalid("cursor", "Курсор относится к другому запросу.");
            }
            if (Instant.parse(snapshot.path("expiresAt").asText()).isBefore(Instant.now())) {
                throw ApiException.problem(HttpStatus.GONE, "CATALOG_CURSOR_EXPIRED", "Обновите каталог.");
            }
            if (!snapshot.path("versions").equals(versions(ids))
                    || !snapshot.path("connected").equals(json.valueToTree(ids))) {
                throw ApiException.problem(HttpStatus.CONFLICT, "CATALOG_SNAPSHOT_STALE", "Каталог обновился. Обновите список.");
            }
            var rows = jdbc.sql("""
                    SELECT data FROM catalog_read_snapshot_items WHERE snapshot_id=:id AND position>=:position
                    ORDER BY position LIMIT :limit
                    """).param("id", snapshotId).param("position", position).param("limit", limit)
                    .query(String.class).list().stream().map(json::readTree).toList();
            int total = snapshot.path("total").asInt();
            boolean more = position + rows.size() < total;
            var response = json.createObjectNode();
            response.set("items", json.valueToTree(rows));
            var page = response.putObject("page").put("limit", limit).put("total", total).put("hasMore", more)
                    .put("snapshotId", snapshotId.toString()).put("capturedAt", snapshot.path("capturedAt").asText())
                    .put("expiresAt", snapshot.path("expiresAt").asText());
            if (more) page.put("nextCursor", snapshotId + ":" + (position + rows.size()));
            else page.putNull("nextCursor");
            var states = new ArrayList<JsonNode>();
            for (MangaSource source : connected) states.add(coverage(source.id(), languages));
            response.set("sourceStates", json.valueToTree(states));
            response.put("partial", states.stream().anyMatch(state -> !"COMPLETE".equals(state.path("metadataState").asText())
                    || !state.path("error").isNull()
                    || state.path("verifiedTitleCount").asLong() < state.path("collectedTitleCount").asLong()));
            return response;
        });
    }

    public JsonNode coverage(String source, List<String> languages) {
        return json.readTree(jdbc.sql("""
                WITH stats AS (
                    SELECT count(*) AS collected,
                        count(*) FILTER(WHERE a->>'state'='COMPLETE') AS verified,
                        count(*) FILTER(WHERE a->>'presence'='NONE') AS hidden
                    FROM titles t JOIN source_catalog_state s ON s.source_id=t.source_id
                    CROSS JOIN LATERAL (SELECT catalog_availability(t.id,
                        CASE WHEN cardinality(CAST(:languages AS text[]))=0 THEN s.languages
                             ELSE ARRAY(SELECT unnest(s.languages) INTERSECT SELECT unnest(CAST(:languages AS text[]))) END) a) av
                    WHERE t.source_id=:source
                ), latest AS (
                    SELECT * FROM catalog_sync_runs WHERE source_id=:source ORDER BY started_at DESC LIMIT 1
                )
                SELECT jsonb_build_object('sourceId',:source,'metadataState',
                    CASE WHEN r.stop_reason='EXHAUSTED' THEN 'COMPLETE'
                         WHEN r.state='FAILED' THEN 'ERROR'
                         WHEN r.id IS NULL THEN 'NOT_STARTED' ELSE 'PARTIAL' END,
                    'collectedTitleCount',collected,'availableTitleCount',collected-hidden,
                    'verifiedTitleCount',verified,'hiddenTitleCount',hidden,'sourceTotal',r.source_total,
                    'stopReason',r.stop_reason,'lastSuccessAt',
                    CASE WHEN r.stop_reason='EXHAUSTED' THEN r.updated_at END,'error',r.error)
                FROM stats LEFT JOIN latest r ON true
                """).param("source", source).param("languages", CatalogSyncService.array(languages))
                .query(String.class).single());
    }

    private UUID createSnapshot(List<String> sources, String query, List<String> tags, String sort,
                                List<String> languages, String queryHash) {
        jdbc.sql("DELETE FROM catalog_read_snapshots WHERE expires_at<now()").update();
        jdbc.sql("""
                DELETE FROM catalog_read_snapshots WHERE id IN(
                    SELECT id FROM catalog_read_snapshots ORDER BY captured_at DESC OFFSET 19)
                """).update();
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog_read_snapshots(id,query_hash,source_versions,connected_sources)
                VALUES(:id,:hash,CAST(:versions AS jsonb),CAST(:sources AS text[]))
                """).param("id", id).param("hash", queryHash).param("versions", json.writeValueAsString(versions(sources)))
                .param("sources", CatalogSyncService.array(sources)).update();
        if (sources.isEmpty()) return id;
        String order = switch (sort) {
            case "year_asc" -> "release_year ASC NULLS LAST";
            case "year_desc" -> "release_year DESC NULLS LAST";
            case "latest_chapter_desc" -> "latest_chapter_at DESC NULLS LAST";
            default -> query.isBlank() ? "lower(title)" : "rank DESC";
        };
        String tagFilter = tags.isEmpty() ? "" : """
                AND (SELECT count(DISTINCT external_tag_id) FROM title_tags tt
                    WHERE tt.title_id=t.id AND tt.external_tag_id IN (:tags))=:tagCount
                """;
        var statement = jdbc.sql("""
                INSERT INTO catalog_read_snapshot_items(snapshot_id,position,data)
                WITH matching AS (
                    SELECT t.*,a.availability,
                        (SELECT max(published_at) FROM source_chapters c WHERE c.title_id=t.id AND c.languages && a.languages)
                            AS latest_chapter_at,
                        ts_rank(t.search_document,websearch_to_tsquery('simple',:q))+
                        ts_rank(t.search_document,websearch_to_tsquery('russian',:q)) AS rank
                    FROM titles t JOIN source_catalog_state s ON s.source_id=t.source_id
                    CROSS JOIN LATERAL (SELECT CASE WHEN cardinality(CAST(:languages AS text[]))=0 THEN s.languages
                        ELSE ARRAY(SELECT unnest(s.languages) INTERSECT SELECT unnest(CAST(:languages AS text[]))) END languages) l
                    CROSS JOIN LATERAL (SELECT catalog_availability(t.id,l.languages) availability,l.languages) a
                    WHERE t.source_id IN (:sources) AND cardinality(a.languages)>0
                    AND a.availability->>'presence'<>'NONE'
                    AND (:q='' OR t.search_document@@websearch_to_tsquery('simple',:q)
                         OR t.search_document@@websearch_to_tsquery('russian',:q)
                         OR lower(t.title || ' ' || coalesce(t.title_ru,'') || ' ' || coalesce(t.title_en,'')) LIKE :substring)
                """ + tagFilter + """
                )
                SELECT :snapshot,(row_number() OVER(ORDER BY
                """ + order + """
                ,source_id,external_title_id)-1)::integer,
                    jsonb_build_object('sourceId',source_id,'externalTitleId',external_title_id,'sourceName',source_name,
                    'title',title,'author',author,'coverUrl',cover_url,'titleRu',title_ru,'titleEn',title_en,
                    'publicationStatus',publication_status,'releaseYear',release_year,
                    'chapterAvailability',availability,'latestChapterAt',latest_chapter_at)
                FROM matching
                """).param("q", query).param("substring", "%" + query.toLowerCase(java.util.Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
                .param("sources", sources).param("languages", CatalogSyncService.array(languages)).param("snapshot", id);
        if (!tags.isEmpty()) statement.param("tags", tags).param("tagCount", tags.size());
        int total = statement.update();
        jdbc.sql("UPDATE catalog_read_snapshots SET total=:total WHERE id=:id")
                .param("total", total).param("id", id).update();
        return id;
    }

    private JsonNode snapshot(UUID id) {
        return jdbc.sql("""
                SELECT jsonb_build_object('queryHash',query_hash,'versions',source_versions,'connected',connected_sources,
                'total',total,'capturedAt',captured_at,'expiresAt',expires_at) FROM catalog_read_snapshots WHERE id=:id
                """).param("id", id).query(String.class).optional().map(json::readTree).orElseThrow(() ->
                ApiException.problem(HttpStatus.GONE, "CATALOG_CURSOR_EXPIRED", "Обновите каталог."));
    }

    private JsonNode versions(List<String> sources) {
        if (sources.isEmpty()) return json.createObjectNode();
        return json.readTree(jdbc.sql("""
                SELECT coalesce(jsonb_object_agg(source_id,visibility_version),'{}') FROM source_catalog_state
                WHERE source_id IN (:sources)
                """).param("sources", sources).query(String.class).single());
    }

    void validateTags(String source, List<String> tags) {
        if (source == null) throw ApiException.catalogSourceRequired();
        if (!connections.requireConnectedAdapter(source).supportsTags()) throw ApiException.problem(
                HttpStatus.UNPROCESSABLE_CONTENT, "CATALOG_OPTIONS_UNSUPPORTED", "Источник не поддерживает теги.");
        var known = jdbc.sql("SELECT external_tag_id FROM source_tags WHERE source_id=:source")
                .param("source", source).query(String.class).list();
        var invalid = tags.stream().filter(tag -> !known.contains(tag)).toList();
        if (!invalid.isEmpty()) throw ApiException.invalidTags(invalid);
    }
}
