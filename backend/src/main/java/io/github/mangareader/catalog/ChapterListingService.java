package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.library.TitleRepository;
import io.github.mangareader.source.MangaSourceRegistry;
import io.github.mangareader.source.SourceModels.SourceTitle;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ChapterListingService {
    private final CatalogSyncService sync;
    private final TitleRepository titles;
    private final MangaSourceRegistry sources;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ChapterListingService(CatalogSyncService sync, TitleRepository titles, MangaSourceRegistry sources,
                                 JdbcClient jdbc, JsonMapper json) {
        this.sync = sync;
        this.titles = titles;
        this.sources = sources;
        this.jdbc = jdbc;
        this.json = json;
    }

    public SourceTitle ensureTitle(String source, String external) {
        return titles.find(source, external).orElseGet(() ->
                titles.sync(List.of(sources.require(source).getTitle(external))).getFirst());
    }

    @org.springframework.transaction.annotation.Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public JsonNode listing(String source, String external, String language, int limit, String cursor) {
        ensureTitle(source, external);
        UUID title = sync.titleId(source, external);
        var languages = language == null ? sources.require(source).supportedLanguages() : List.of(language);
        if (!sources.require(source).supportedLanguages().containsAll(languages)) {
            throw ApiException.unavailableLanguage(sources.require(source).supportedLanguages());
        }
        if (limit < 1 || limit > 500) throw CatalogSyncService.invalid("limit", "Размер страницы от 1 до 500.");
        var availability = sync.availability(title, languages);
        if ("UNVERIFIED".equals(availability.path("state").asText())) sync.enqueue(source, external);
        String revision = jdbc.sql("""
                SELECT md5(coalesce(string_agg(source_chapter_id || data::text,'' ORDER BY source_chapter_id),''))
                FROM source_chapters WHERE title_id=:id AND languages && CAST(:languages AS text[])
                """).param("id", title).param("languages", CatalogSyncService.array(languages)).query(String.class).single();
        revision = CatalogSyncService.hash(title + ":" + languages + ":" + limit + ":" + revision);
        int offset = 0;
        if (cursor != null) {
            try {
                String[] parts = cursor.split(":", -1);
                if (parts.length != 2) throw new IllegalArgumentException();
                offset = Integer.parseInt(parts[1]);
                if (offset < 0) throw new IllegalArgumentException();
                if (!revision.equals(parts[0])) throw ApiException.problem(HttpStatus.CONFLICT,
                        "CHAPTER_LIST_CHANGED", "Список глав обновился. Повторите загрузку.");
            }
            catch (IllegalArgumentException exception) { throw CatalogSyncService.invalid("cursor", "Неверная позиция главы."); }
        }
        var rows = jdbc.sql("""
                SELECT data FROM source_chapters WHERE title_id=:id AND languages && CAST(:languages AS text[])
                ORDER BY CASE WHEN data->>'volume' ~ '^[0-9]+([.][0-9]+)?$'
                              THEN (data->>'volume')::numeric END NULLS FIRST,
                         coalesce(data->>'volume',''),
                         CASE WHEN data->>'number' IS NOT NULL THEN (data->>'number')::numeric END NULLS LAST,
                         source_chapter_id LIMIT :limit OFFSET :offset
                """).param("id", title).param("languages", CatalogSyncService.array(languages))
                .param("limit", limit + 1).param("offset", offset).query(String.class).list();
        if (rows.isEmpty() && "ERROR".equals(availability.path("state").asText())) {
            throw ApiException.withDetails(HttpStatus.SERVICE_UNAVAILABLE, "CHAPTER_LIST_UNAVAILABLE",
                    availability.path("error").path("detail").asText("Не удалось получить список глав."),
                    java.util.Map.of("availability", availability, "stage", "CHAPTERS", "sourceId", source));
        }
        boolean more = rows.size() > limit;
        var response = json.createObjectNode();
        response.set("items", json.valueToTree(rows.stream().limit(limit).map(json::readTree).toList()));
        response.set("availability", availability);
        var page = response.putObject("page").put("limit", limit).put("hasMore", more);
        if (more) page.put("nextCursor", revision + ":" + (offset + limit));
        else page.putNull("nextCursor");
        return response;
    }
}
