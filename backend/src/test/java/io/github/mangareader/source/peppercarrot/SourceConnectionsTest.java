package io.github.mangareader.source.peppercarrot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.mangareader.source.MangaSource;
import io.github.mangareader.source.SourceModels.SourceTitle;
import io.github.mangareader.source.SourceModels.Tag;
import io.github.mangareader.library.TitleRepository;
import io.github.mangareader.library.TitleTagRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(SourceConnectionsTest.OtherAdapterConfiguration.class)
class SourceConnectionsTest {
    private static final String PEPPER = "/sources/peppercarrot";
    private static final String TITLE = PEPPER + "/titles/pepper-and-carrot";
    private static final String PAGES = TITLE + "/chapters/ep01_Potion-of-Flight/pages?language=ru";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.12-bookworm");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TitleRepository titles;

    @Autowired
    TitleTagRepository tags;

    @MockitoBean
    PepperCarrotClient sourceClient;

    @Autowired
    PepperCarrotSource pepperSource;

    @BeforeEach
    void seedSavedCatalog() {
        titles.syncCatalog(List.of(pepperSource.getTitle("pepper-and-carrot")));
    }

    @Test
    void migrationEnablesPepperAndListsReturnMetadataInStableOrder() {
        assertThat(jdbc.sql("SELECT success FROM flyway_schema_history WHERE version = '3'")
                .query(Boolean.class).single()).isTrue();
        JsonNode extensions = get("/extensions").path("items");
        assertThat(extensions).extracting(item -> item.path("sourceId").stringValue())
                .containsExactly("a-test", "mangadex", "mangakakalot", "mangalib", "peppercarrot");
        assertThat(extensions.get(0).path("enabled").asBoolean()).isFalse();
        assertThat(extensions.get(0).get("websiteUrl").isNull()).isTrue();
        assertThat(extensions.get(1).path("enabled").asBoolean()).isFalse();
        assertThat(extensions.get(2).path("enabled").asBoolean()).isFalse();
        assertThat(extensions.get(3).path("enabled").asBoolean()).isFalse();
        assertThat(extensions.get(4).path("enabled").asBoolean()).isTrue();
        JsonNode sources = get("/sources").path("items");
        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).path("sourceId").stringValue()).isEqualTo("peppercarrot");
        assertThat(sources.get(0).path("sourceName").stringValue()).isEqualTo("Pepper&Carrot");
        assertThat(sources.get(0).path("websiteUrl").stringValue()).isEqualTo(PepperCarrotClient.ORIGIN);
        assertThat(sources.get(0).has("enabled")).isFalse();
        verifyNoInteractions(sourceClient);
    }

    @Test
    void unknownAndMalformedIdsUseProblemDetails() {
        for (String method : List.of("PUT", "DELETE")) {
            problem(method, "/sources/unknown", 404, "SOURCE_NOT_FOUND");
            JsonNode invalid = problem(method, "/sources/BAD_id", 400, "VALIDATION_ERROR");
            assertThat(invalid.path("errors").get(0).path("field").stringValue()).isEqualTo("sourceId");
        }
        problem("GET", "/catalog?sourceId=unknown", 404, "SOURCE_NOT_FOUND");
    }

    @Test
    void concurrentRepeatedChangesKeepOneConnectionAndFilterCatalog() {
        try {
            assertCodesInParallel("DELETE", 204);
            assertThat(get("/sources").path("items").isEmpty()).isTrue();
            assertThat(get("/extensions").path("items").get(2).path("enabled").asBoolean()).isFalse();
            assertThat(get("/catalog").path("items").isEmpty()).isTrue();
            problem("GET", "/catalog?sourceId=peppercarrot", 409, "SOURCE_DISABLED");
            assertCodesInParallel("PUT", 200);
            assertThat(json(request("PUT", PEPPER, null), 200).path("sourceId").stringValue())
                    .isEqualTo("peppercarrot");
            assertThat(jdbc.sql("SELECT count(*) FROM connected_sources WHERE source_id = 'peppercarrot'")
                    .query(Long.class).single()).isEqualTo(1L);
            assertThat(get("/catalog?q=pepper&sourceId=peppercarrot").path("items")).hasSize(1);
            assertThat(get("/catalog?q=absent").path("items").isEmpty()).isTrue();
            verifyNoInteractions(sourceClient);
        } finally {
            json(request("PUT", PEPPER, null), 200);
        }
    }

    @Test
    void disconnectPreservesLibraryProgressAndChapterAccess() {
        when(sourceClient.get("/0_sources/episodes.json")).thenReturn("""
                [{"name":"ep01_Potion-of-Flight","total_pages":0,"translated_languages":["ru"]}]
                """);
        when(sourceClient.get("/ru/webcomic/ep01_Potion-of-Flight.html")).thenReturn("""
                <title>Эпизод 1: Зелье полета - Pepper&amp;Carrot</title>
                <div class="webcomic-page">
                    <img src="/0_sources/ep01_Potion-of-Flight/low-res/ru_Pepper-and-Carrot_by-David-Revoy_E01P00.jpg">
                </div>
                """);
        JsonNode created = json(request("POST", "/library", """
                {"sourceId":"peppercarrot","externalTitleId":"pepper-and-carrot","status":"READING","note":"Закладка"}
                """), 201);
        String entry = "/library/" + created.path("id").stringValue();
        try {
            json(request("PATCH", entry, """
                    {"progress":{"chapterId":"ep01_Potion-of-Flight","language":"ru","pageIndex":0}}
                    """), 200);
            JsonNode saved = get(entry);
            assertThat(saved.path("note").stringValue()).isEqualTo("Закладка");
            assertThat(saved.path("progress").path("pageIndex").asInt()).isZero();
            assertThat(request("DELETE", PEPPER, null).statusCode()).isEqualTo(204);
            assertThat(get(entry)).isEqualTo(saved);
            assertThat(get(TITLE).path("externalTitleId").stringValue()).isEqualTo("pepper-and-carrot");
            get(TITLE + "/chapters");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(6);
            while (get(TITLE + "/chapters").path("items").isEmpty() && System.nanoTime() < deadline) {
                try { Thread.sleep(100); } catch (InterruptedException exception) { throw new IllegalStateException(exception); }
            }
            assertThat(get(TITLE + "/chapters").path("items")).hasSize(1);
            assertThat(get(PAGES).path("pages")).hasSize(1);
        } finally {
            assertThat(request("DELETE", entry, null).statusCode()).isEqualTo(204);
            json(request("PUT", PEPPER, null), 200);
        }
    }

    @Test
    void librarySectionsKeepMembershipIndependentOfReadingState() {
        String sections = "/library/sections";
        JsonNode entry = json(request("POST", "/library", """
                {"sourceId":"peppercarrot","externalTitleId":"pepper-and-carrot","status":"READING","note":"Закладка"}
                """), 201);
        String entryId = entry.path("id").stringValue();
        String entryPath = "/library/" + entryId;
        jdbc.sql("""
                UPDATE library_entries SET progress_chapter_id = 'ep01_Potion-of-Flight',
                    progress_language = 'ru', progress_page_index = 0 WHERE id = :id
                """).param("id", UUID.fromString(entryId)).update();

        String weekend = sections + "/" + json(request("POST", sections, """
                {"name":" На выходные "}
                """), 201).path("id").asString();
        String later = sections + "/" + json(request("POST", sections, """
                {"name":"На каникулы"}
                """), 201).path("id").asString();
        String membership = "{\"entryIds\":[\"" + entryId + "\"]}";
        try {
            json(request("POST", sections, "{\"name\":\"НА ВЫХОДНЫЕ\"}"), 409);
            json(request("POST", sections, "{\"name\":\"Читаю\"}"), 409);
            json(request("POST", weekend + "/entries",
                    "{\"entryIds\":[\"" + entryId + "\",\"" + UUID.randomUUID() + "\"]}"), 404);
            assertThat(get(weekend).path("entryIds")).isEmpty();

            for (String path : List.of(weekend, weekend, later)) {
                assertThat(json(request("POST", path + "/entries", membership), 200).path("entryIds"))
                        .extracting(JsonNode::asString).containsExactly(entryId);
            }
            JsonNode updated = json(request("PATCH", entryPath, "{\"status\":\"COMPLETED\"}"), 200);
            assertThat(updated.path("status").asString()).isEqualTo("COMPLETED");
            assertThat(updated.path("note").asString()).isEqualTo("Закладка");
            assertThat(updated.path("progress").path("pageIndex").asInt()).isZero();
            JsonNode persisted = get(entryPath);

            JsonNode renamed = json(request("PATCH", weekend, "{\"name\":\"На субботу\"}"), 200);
            assertThat(renamed.path("name").asString()).isEqualTo("На субботу");
            assertThat(renamed.path("entryIds")).extracting(JsonNode::asString).containsExactly(entryId);
            assertThat(json(request("DELETE", later + "/entries/" + entryId, null), 200).path("entryIds")).isEmpty();
            assertThat(request("DELETE", weekend, null).statusCode()).isEqualTo(204);
            assertThat(get(entryPath)).isEqualTo(persisted);

            json(request("POST", later + "/entries", membership), 200);
            assertThat(request("DELETE", entryPath, null).statusCode()).isEqualTo(204);
            assertThat(get(later).path("entryIds")).isEmpty();
        } finally {
            request("DELETE", weekend, null);
            request("DELETE", later, null);
            request("DELETE", entryPath, null);
        }
    }

    @Test
    void systemSectionNamesCanChangeWithoutChangingTheirIdentity() {
        String path = "/library/sections/b7311428-7138-4c13-9ace-000000000002";
        JsonNode before = get(path);
        try {
            JsonNode renamed = json(request("PATCH", path, "{\"name\":\"Сейчас читаю\"}"), 200);
            assertThat(renamed.path("systemKey").asString()).isEqualTo("reading");
            assertThat(renamed.path("id")).isEqualTo(before.path("id"));
            assertThat(get(path).path("name").asString()).isEqualTo("Сейчас читаю");
            json(request("PATCH", path, "{\"name\":\"Все тайтлы\"}"), 400);
            json(request("PATCH", path, "{\"name\":\"Скачанное\"}"), 409);
            json(request("DELETE", path, null), 422);
            json(request("POST", path + "/entries", "{\"entryIds\":[\"" + UUID.randomUUID() + "\"]}"), 422);
        } finally {
            json(request("PATCH", path, "{\"name\":\"Читаю\"}"), 200);
        }
    }

    @Test
    void catalogPersistsBothTitlesWithoutAddingLibraryEntries() {
        String endpoint = "/catalog?sourceId=peppercarrot&q=pepper";
        JsonNode library = get("/library");
        JsonNode first = get(endpoint).path("items").get(0);
        assertThat(first.path("titleRu").stringValue()).isEqualTo("Перчинка и Морквик");
        assertThat(first.path("titleEn").stringValue()).isEqualTo("Pepper&Carrot");
        UUID id = jdbc.sql("""
                SELECT id FROM titles WHERE source_id = 'peppercarrot' AND external_title_id = 'pepper-and-carrot'
                """).query(UUID.class).single();

        assertThat(get(endpoint).path("items").get(0).path("titleRu")).isEqualTo(first.path("titleRu"));
        assertThat(jdbc.sql("SELECT title_ru FROM titles WHERE id = :id").param("id", id)
                .query(String.class).single()).isEqualTo(first.path("titleRu").stringValue());
        assertThat(get("/library")).isEqualTo(library);
    }

    @Test
    void tagBackfillRetainsTheTitleAndRejectsOlderSnapshots() {
        String id = "tag-backfill";
        Instant now = Instant.now();
        Tag first = new Tag("first", "genre", Map.of("en", "First"), 1);
        Tag renamed = new Tag("first", "genre", Map.of("en", "New name"), 2);
        titles.sync(List.of(tagTitle(id, List.of(), null, null)));
        UUID localId = jdbc.sql("SELECT id FROM titles WHERE source_id = 'mangadex' AND external_title_id = :id")
                .param("id", id).query(UUID.class).single();
        assertThat(tags.unsyncedMangaDexIds("")).contains(id);
        UUID entry = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO library_entries (id, title_id, status, note, created_at, updated_at)
                VALUES (:id, :title, 'READING', 'Keep note', now(), now())
                """).param("id", entry).param("title", localId).update();
        try {
            titles.sync(List.of(tagTitle(id, List.of(first), 1, now)));
            assertThat(tags.unsyncedMangaDexIds("")).doesNotContain(id);
            assertThat(tags.forTitle("mangadex", id)).extracting(Tag::id).containsExactly("first");
            titles.sync(List.of(tagTitle(id, List.of(renamed), 2, now.plusSeconds(1))));
            titles.sync(List.of(tagTitle(id, List.of(), 1, now.plusSeconds(2))));
            titles.sync(List.of(tagTitle(id, List.of(), null, null)));
            assertThat(tags.forTitle("mangadex", id).getFirst().names()).containsEntry("en", "New name");
            titles.sync(List.of(tagTitle(id, List.of(), 3, now.plusSeconds(3))));
            assertThat(tags.forTitle("mangadex", id)).isEmpty();
            assertThat(jdbc.sql("SELECT title_id FROM library_entries WHERE id = :id")
                    .param("id", entry).query(UUID.class).single()).isEqualTo(localId);
        } finally {
            jdbc.sql("DELETE FROM library_entries WHERE id = :id").param("id", entry).update();
            jdbc.sql("DELETE FROM titles WHERE id = :id").param("id", localId).update();
        }
    }

    @Test
    void catalogOptionsRespectSourceCapabilitiesAndValidateSyntax() {
        problem("GET", "/catalog?sort=year_desc", 400, "SOURCE_REQUIRED_FOR_CATALOG_OPTIONS");
        problem("GET", "/catalog?sourceId=peppercarrot&tagId=invalid", 400, "VALIDATION_ERROR");
        assertThat(get("/catalog?sourceId=peppercarrot&sort=year_desc").path("page").path("total").asInt()).isPositive();
        problem("GET", "/sources/peppercarrot/tags", 422, "CATALOG_OPTIONS_UNSUPPORTED");
        problem("GET", "/sources/mangadex/tags", 409, "SOURCE_DISABLED");
    }

    @Test
    void catalogHidesOnlyCompleteEmptyChecksAndInvalidatesOldSnapshots() {
        String prefix = "catalog-regression-";
        var fixtures = java.util.stream.IntStream.range(0, 3).mapToObj(index -> new SourceTitle(
                "peppercarrot", prefix + index, "Pepper&Carrot", prefix + index, null, null, null,
                "https://www.peppercarrot.com/", null, null, null, null, null)).toList();
        titles.syncCatalog(fixtures);
        var fixtureIds = jdbc.sql("SELECT id FROM titles WHERE external_title_id LIKE 'catalog-regression-%'")
                .query(UUID.class).list();
        try {
            UUID empty = jdbc.sql("SELECT id FROM titles WHERE external_title_id=:id")
                    .param("id", prefix + "0").query(UUID.class).single();
            for (String language : List.of("ru", "en")) jdbc.sql("""
                    INSERT INTO title_chapter_checks(title_id,language,policy_version,state,presence,exact_count)
                    VALUES(:id,:language,'catalog-v1','COMPLETE','NONE',0)
                    """).param("id", empty).param("language", language).update();
            var response = get("/catalog?sourceId=peppercarrot&q=catalog-regression&limit=1");
            assertThat(response.path("page").path("total").asInt()).isEqualTo(2);
            assertThat(response.path("page").path("hasMore").asBoolean()).isTrue();
            assertThat(response.path("items").get(0).path("chapterAvailability").path("chapterCount").isNull()).isTrue();
            String cursor = response.path("page").path("nextCursor").asString();
            var next = get("/catalog?sourceId=peppercarrot&q=catalog-regression&limit=1&cursor=" + cursor);
            assertThat(next.path("items")).hasSize(1);
            assertThat(next.path("page").path("hasMore").asBoolean()).isFalse();
            assertThat(next.path("items").get(0).path("externalTitleId"))
                    .isNotEqualTo(response.path("items").get(0).path("externalTitleId"));
            jdbc.sql("UPDATE source_catalog_state SET visibility_version=visibility_version+1 WHERE source_id='peppercarrot'").update();
            problem("GET", "/catalog?sourceId=peppercarrot&q=catalog-regression&limit=1&cursor=" + cursor,
                    409, "CATALOG_SNAPSHOT_STALE");
            jdbc.sql("UPDATE title_chapter_checks SET state='ERROR',presence='UNKNOWN',exact_count=NULL WHERE title_id=:id")
                    .param("id", empty).update();
            assertThat(get("/catalog?sourceId=peppercarrot&q=catalog-regression").path("page").path("total").asInt()).isEqualTo(3);
        } finally {
            for (UUID id : fixtureIds) jdbc.sql("DELETE FROM titles WHERE id=:id").param("id", id).update();
        }
    }

    @Test
    void catalogRunIsIdempotentAndPauseKeepsItsCheckpoint() {
        var started = json(request("POST", "/sources/peppercarrot/catalog-sync", "{\"mode\":\"FULL\"}"), 202);
        String id = started.path("runId").asString();
        String runPath = "/sources/peppercarrot/catalog-sync/" + id;
        try {
            assertThat(json(request("POST", "/sources/peppercarrot/catalog-sync", "{\"mode\":\"FULL\"}"), 202)
                    .path("runId").asString()).isEqualTo(id);
            var paused = json(request("POST", runPath + "/pause", null), 202);
            assertThat(paused.path("state").asString()).isEqualTo("PAUSED");
            var resumed = json(request("POST", runPath + "/resume", null), 202);
            assertThat(resumed.path("runId").asString()).isEqualTo(id);
            assertThat(resumed.path("pagesCommitted")).isEqualTo(paused.path("pagesCommitted"));
            json(request("POST", runPath + "/pause", null), 202);
        } finally {
            jdbc.sql("DELETE FROM catalog_sync_runs WHERE id=:id").param("id", UUID.fromString(id)).update();
        }
    }

    @Test
    void chapterCursorRejectsAChangedListInsteadOfSkippingRows() {
        String external = "chapter-cursor-regression";
        titles.syncCatalog(List.of(new SourceTitle("peppercarrot", external,"Pepper&Carrot", external,
                null,null,null,"https://www.peppercarrot.com/",null,null,null,null,null)));
        UUID id = jdbc.sql("SELECT id FROM titles WHERE external_title_id=:external")
                .param("external", external).query(UUID.class).single();
        try {
            for (String language : List.of("ru","en")) jdbc.sql("""
                    INSERT INTO title_chapter_checks(title_id,language,policy_version,state,presence,exact_count)
                    VALUES(:id,:language,'catalog-v1','COMPLETE','FOUND',2)
                    """).param("id", id).param("language", language).update();
            for (String chapter : List.of("1","2")) jdbc.sql("""
                    INSERT INTO source_chapters VALUES(:id,:chapter,:chapter,ARRAY['ru'],NULL,
                        jsonb_build_object('id',:chapter,'number',CAST(:chapter AS numeric),'languages',ARRAY['ru']))
                    """).param("id", id).param("chapter", chapter).update();
            String path = "/sources/peppercarrot/titles/" + external + "/chapters?limit=1";
            String cursor = get(path).path("page").path("nextCursor").asString();
            assertThat(get(path + "&cursor=" + cursor).path("items").get(0).path("id").asString()).isEqualTo("2");
            jdbc.sql("DELETE FROM source_chapters WHERE title_id=:id AND source_chapter_id='1'").param("id",id).update();
            problem("GET",path + "&cursor=" + cursor,409,"CHAPTER_LIST_CHANGED");
        } finally { jdbc.sql("DELETE FROM titles WHERE id=:id").param("id",id).update(); }
    }

    @Test
    void repeatedDownloadAndRetryReuseTheSameJobAndDeleteIt() throws InterruptedException {
        when(sourceClient.get("/0_sources/episodes.json")).thenReturn("""
                [{"name":"ep01_Potion-of-Flight","total_pages":0,"translated_languages":["ru"]}]
                """);
        String body = """
                {"sourceId":"peppercarrot","externalTitleId":"pepper-and-carrot","chapterId":"ep999_Unknown","language":"ru"}
                """;
        var first = send("POST", "/downloads", body);
        var second = send("POST", "/downloads", body);
        var firstResponse = first.join();
        var secondResponse = second.join();
        assertThat(firstResponse.statusCode()).isIn(200, 202);
        assertThat(secondResponse.statusCode()).isIn(200, 202);
        String id = JSON.readTree(firstResponse.body()).path("id").stringValue();
        assertThat(JSON.readTree(secondResponse.body()).path("id").stringValue()).isEqualTo(id);
        String path = "/downloads/" + id;
        assertThat(awaitDownload(path, "FAILED").path("error").path("code").stringValue())
                .isEqualTo("CHAPTER_NOT_FOUND");
        problem("GET", path + "/pages", 409, "DOWNLOAD_NOT_READY");
        assertThat(json(request("POST", path + "/retry", null), 202).path("id").stringValue()).isEqualTo(id);
        assertThat(awaitDownload(path, "FAILED").path("error").path("code").stringValue())
                .isEqualTo("CHAPTER_NOT_FOUND");
        json(request("DELETE", path, null), 202);
        awaitDownload(path, "REMOVED");
        assertThat(request("DELETE", path, null).statusCode()).isEqualTo(204);
    }

    private JsonNode awaitDownload(String path, String state) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            var response = request("GET", path, null);
            if ("REMOVED".equals(state) && response.statusCode() == 404) return null;
            JsonNode item = json(response, 200);
            if (state.equals(item.path("status").stringValue())) return item;
            Thread.sleep(100);
        }
        throw new AssertionError("Download did not enter " + state);
    }

    private SourceTitle tagTitle(String id, List<Tag> snapshot, Integer version, Instant time) {
        return new SourceTitle("mangadex", id, "MangaDex", "Test title", null, null, null,
                "https://mangadex.org", null, null, null, null, null, snapshot, version, time);
    }

    private void assertCodesInParallel(String method, int status) {
        var requests = IntStream.range(0, 8).mapToObj(index -> send(method, PEPPER, null)).toList();
        for (var response : requests) {
            assertThat(response.join().statusCode()).isEqualTo(status);
        }
    }

    private JsonNode get(String path) {
        return json(request("GET", path, null), 200);
    }

    private JsonNode problem(String method, String path, int status, String code) {
        var response = request(method, path, null);
        assertThat(response.headers().firstValue("content-type")).contains("application/problem+json");
        JsonNode problem = json(response, status);
        assertThat(problem.path("code").stringValue()).isEqualTo(code);
        return problem;
    }

    private JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> request(String method, String path, String body) {
        return send(method, path, body).join();
    }

    private CompletableFuture<HttpResponse<String>> send(String method, String path, String body) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json; charset=UTF-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OtherAdapterConfiguration {
        @Bean
        MangaSource testAdapter() {
            MangaSource adapter = mock(MangaSource.class);
            when(adapter.id()).thenReturn("a-test");
            when(adapter.displayName()).thenReturn("Учебный адаптер");
            when(adapter.policyVersion()).thenReturn("catalog-v1");
            when(adapter.supportedLanguages()).thenReturn(List.of("ru", "en"));
            return adapter;
        }
    }
}
