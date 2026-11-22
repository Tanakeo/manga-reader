package io.github.mangareader.catalog;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.mangareader.error.ApiException;
import io.github.mangareader.source.MangaSourceRegistry;
import io.github.mangareader.source.PublicSourceClient;
import io.github.mangareader.source.SourceModels.ChapterPages;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class ReaderService {
    private final MangaSourceRegistry sources;
    private final PublicSourceClient client;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Cache<Key, Object> locks = Caffeine.newBuilder().maximumSize(256).expireAfterAccess(Duration.ofMinutes(10)).build();
    private final Cache<String, java.util.concurrent.Semaphore> hostSlots =
            Caffeine.newBuilder().maximumSize(64).expireAfterAccess(Duration.ofHours(1)).build();

    public ReaderService(MangaSourceRegistry sources, PublicSourceClient client, JdbcClient jdbc, JsonMapper json) {
        this.sources = sources;
        this.client = client;
        this.jdbc = jdbc;
        this.json = json;
    }

    public JsonNode pages(String source, String title, String chapter, String language, boolean refresh) {
        if (language == null) language = sources.require(source).supportedLanguages().getFirst();
        Key key = new Key(source, title, chapter, language);
        Manifest manifest = manifest(key, refresh);
        var result = (tools.jackson.databind.node.ObjectNode) json.valueToTree(manifest.pages());
        result.put("manifestVersion", manifest.version()).put("fetchedAt", manifest.fetched().toString())
                .put("expiresAt", manifest.expires().toString());
        var pages = result.putArray("pages");
        for (var page : manifest.pages().pages()) {
            pages.addObject().put("index", page.index()).put("imageUrl",
                    "/api/v1/sources/" + source + "/titles/" + title + "/chapters/" + chapter
                            + "/images/" + page.index() + "?language=" + language + "&manifestVersion=" + manifest.version());
        }
        return result;
    }

    public PublicSourceClient.Image image(String source, String title, String chapter, String language,
                                         int index, String version) {
        Key key = new Key(source, title, chapter, language);
        Manifest manifest = manifest(key, false);
        if (version != null && !version.equals(manifest.version())) throw changed();
        if (index < 0 || index >= manifest.pages().pageCount()) throw ApiException.problem(
                HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "Страница не найдена.");
        try {
            var image = fetch(key, manifest, index);
            record(key, "SAMPLED_OK", null);
            return image;
        } catch (ApiException first) {
            if ("IMAGE_ACCESS_DENIED".equals(first.code()) || source.equals("mangadex")) {
                record(key, "ERROR", first);
                throw first;
            }
            Manifest updated = manifest(key, true);
            if (!updated.version().equals(manifest.version())) throw changed();
            try {
                var image = fetch(key, updated, index);
                record(key, "SAMPLED_OK", null);
                return image;
            } catch (ApiException error) {
                record(key, "ERROR", error);
                throw ApiException.withDetails(error.status(), error.code(),
                        error.getMessage(), Map.of("stage", "IMAGE", "sourceId", source,
                                "retryable", !error.code().equals("IMAGE_ACCESS_DENIED")));
            }
        }
    }

    private PublicSourceClient.Image fetch(Key key, Manifest manifest, int index) {
        var adapter = sources.require(key.source());
        var image = adapter.serverImage(key.title(), key.chapter(), key.language(), index);
        if (image != null) return image;
        String address = manifest.pages().pages().get(index).imageUrl();
        URI uri = PublicSourceClient.httpsUrl(address, adapter.imageDomains().toArray(String[]::new));
        if (uri == null) throw ApiException.problem(HttpStatus.BAD_GATEWAY, "IMAGE_BAD_RESPONSE", "Недопустимый адрес изображения.");
        try { return download(uri, manifest.pages().attribution().sourceUrl()); }
        catch (ApiException first) {
            for (URI alternative : adapter.alternativeImages(uri)) {
                if (PublicSourceClient.httpsUrl(alternative.toString(), adapter.imageDomains().toArray(String[]::new)) == null) continue;
                try { return download(alternative, manifest.pages().attribution().sourceUrl()); }
                catch (ApiException ignored) { /* Try the remaining configured CDN. */ }
            }
            throw first;
        }
    }

    private PublicSourceClient.Image download(URI uri, String referer) {
        var slots = hostSlots.get(uri.getHost(), ignored -> new java.util.concurrent.Semaphore(2));
        boolean acquired = false;
        try {
            acquired = slots.tryAcquire(1, java.util.concurrent.TimeUnit.SECONDS);
            if (!acquired) throw ApiException.withDetails(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_UNAVAILABLE",
                    "Сервер изображений занят. Повторите загрузку.", Map.of("stage", "IMAGE", "retryable", true, "retryAfterSeconds", 1));
            return client.image(uri, referer);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.problem(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_UNAVAILABLE", "Загрузка прервана.");
        } finally { if (acquired) slots.release(); }
    }

    private Manifest manifest(Key key, boolean refresh) {
        synchronized (locks.get(key, ignored -> new Object())) {
            var saved = query(key).query((row, index) -> new Manifest(
                    json.readValue(row.getString("data"), ChapterPages.class), row.getString("version"),
                    row.getTimestamp("fetched_at").toInstant(), row.getTimestamp("expires_at").toInstant())).optional();
            if (!refresh && saved.isPresent() && saved.get().expires().isAfter(Instant.now())) return saved.get();
            // Coalesce concurrent refreshes, but allow a later explicit retry.
            if (refresh && saved.isPresent() && saved.get().fetched().isAfter(Instant.now().minusSeconds(2))) return saved.get();
            var source = sources.require(key.source());
            source.refreshManifest(key.chapter());
            ChapterPages pages;
            try { pages = source.getPages(key.title(), key.chapter(), key.language()); }
            catch (ApiException exception) {
                if (exception.status().is4xxClientError()) throw exception;
                throw ApiException.withDetails(HttpStatus.SERVICE_UNAVAILABLE, "MANIFEST_UNAVAILABLE", exception.getMessage(),
                        Map.of("stage", "MANIFEST", "sourceId", key.source(), "retryable", true, "causeCode", exception.code()));
            }
            if (pages.pages().isEmpty() || pages.pageCount() != pages.pages().size()) throw ApiException.problem(
                    HttpStatus.BAD_GATEWAY, "MANIFEST_UNAVAILABLE", "Источник вернул неполный список страниц.");
            for (int i = 0; i < pages.pages().size(); i++) if (pages.pages().get(i).index() != i) throw ApiException.problem(
                    HttpStatus.BAD_GATEWAY, "MANIFEST_UNAVAILABLE", "Нарушен порядок страниц.");
            String version = CatalogSyncService.hash(json.writeValueAsString(pages.pages()));
            Instant now = Instant.now();
            jdbc.sql("""
                    INSERT INTO reader_manifests(source_id,external_title_id,chapter_id,language,version,data,fetched_at,expires_at)
                    VALUES(:source,:title,:chapter,:language,:version,CAST(:data AS jsonb),now(),now()+interval '3 minutes')
                    ON CONFLICT(source_id,external_title_id,chapter_id,language) DO UPDATE SET version=excluded.version,
                    data=excluded.data,fetched_at=excluded.fetched_at,expires_at=excluded.expires_at
                    """).params(parameters(key)).param("version", version).param("data", json.writeValueAsString(pages)).update();
            return new Manifest(pages, version, now, now.plusSeconds(180));
        }
    }

    private JdbcClient.StatementSpec query(Key key) {
        return jdbc.sql("""
                SELECT * FROM reader_manifests WHERE source_id=:source AND external_title_id=:title
                AND chapter_id=:chapter AND language=:language
                """).params(parameters(key));
    }

    private void record(Key key, String state, ApiException error) {
        jdbc.sql("""
                UPDATE reader_manifests SET image_check_state=:state,last_error=CAST(:error AS jsonb)
                WHERE source_id=:source AND external_title_id=:title AND chapter_id=:chapter AND language=:language
                """).params(parameters(key)).param("state", state)
                .param("error", error == null ? null : json.writeValueAsString(Map.of("code", error.code(),
                        "detail", error.getMessage(), "stage", "IMAGE"))).update();
    }

    private Map<String, String> parameters(Key key) {
        return Map.of("source", key.source(), "title", key.title(), "chapter", key.chapter(), "language", key.language());
    }

    private ApiException changed() {
        return ApiException.problem(HttpStatus.CONFLICT, "PAGE_MANIFEST_CHANGED", "Список страниц обновился. Откройте главу повторно.");
    }

    private record Key(String source, String title, String chapter, String language) {}
    private record Manifest(ChapterPages pages, String version, Instant fetched, Instant expires) {}
}
