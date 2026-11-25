package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.error.ApiException.FieldError;
import io.github.mangareader.error.ApiInputs;
import io.github.mangareader.library.TitleRepository;
import io.github.mangareader.library.TitleTagRepository;
import io.github.mangareader.source.MangaSource;
import io.github.mangareader.source.MangaSourceRegistry;
import io.github.mangareader.source.SourceConnectionService;
import io.github.mangareader.source.SourceModels.ChapterCount;
import io.github.mangareader.source.SourceModels.ChapterPages;
import io.github.mangareader.source.SourceModels.Items;
import io.github.mangareader.source.SourceModels.SourceChapter;
import io.github.mangareader.source.SourceModels.SourceTitle;
import io.github.mangareader.source.SourceModels.SourceTitleSummary;
import io.github.mangareader.source.SourceModels.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private static final String TITLE_PATH = "/sources/{sourceId}/titles/{externalTitleId}";
    private static final Pattern TAG_ID = Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final Set<String> SORTS = Set.of("relevance", "year_asc", "year_desc", "latest_chapter_desc");

    private final MangaSourceRegistry registry;
    private final SourceConnectionService connections;
    private final TitleRepository titles;
    private final TitleTagRepository titleTags;
    private final LocalCatalogService localCatalog;
    private final CatalogSyncService sync;
    private final ChapterListingService chapterListing;
    private final JsonMapper json;
    private final ReaderService reader;
    private final WorkService works;

    public CatalogController(MangaSourceRegistry registry, SourceConnectionService connections, TitleRepository titles,
                             TitleTagRepository titleTags, LocalCatalogService localCatalog, CatalogSyncService sync,
                             ChapterListingService chapterListing, JsonMapper json, ReaderService reader, WorkService works) {
        this.registry = registry;
        this.connections = connections;
        this.titles = titles;
        this.titleTags = titleTags;
        this.localCatalog = localCatalog;
        this.sync = sync;
        this.chapterListing = chapterListing;
        this.json = json;
        this.reader = reader;
        this.works = works;
    }

    @GetMapping("/catalog")
    public JsonNode catalog(@RequestParam(required = false) String sourceId,
                                             @RequestParam(defaultValue = "") String q,
                                             @RequestParam(name = "tagId", required = false) List<String> tagIds,
                                             @RequestParam(defaultValue = "") String sort,
                                             @RequestParam(name = "chapterLanguage", required = false) List<String> languages,
                                             @RequestParam(defaultValue = "24") int limit,
                                             @RequestParam(required = false) String cursor,
                                             @RequestParam(defaultValue = "sources") String view) {
        if (!List.of("sources","works").contains(view)) throw CatalogSyncService.invalid("view","Неизвестный вид каталога.");
        if (q.length() > 120) {
            throw ApiException.validation(List.of(new FieldError("q", "TOO_LONG", "Введите не более 120 символов.")));
        }
        List<MangaSource> sources = sourceId == null
                ? connections.connectedAdapters()
                : List.of(connections.requireConnectedAdapter(sourceId));
        List<String> tags = tagIds == null ? List.of() : tagIds.stream()
                .map(id -> id.toLowerCase(Locale.ROOT)).distinct().sorted().toList();
        if (tagIds != null && (tagIds.size() > 10 || tags.stream().anyMatch(id -> !TAG_ID.matcher(id).matches()))) {
            throw ApiException.validation(List.of(new FieldError("tagId", "INVALID_TAG_IDS",
                    "Укажите не более десяти корректных UUID тегов.")));
        }
        String order = switch (sort) {
            case "year-desc" -> "year_desc";
            case "latest-chapter" -> "latest_chapter_desc";
            default -> sort;
        };
        if (!order.isEmpty() && !SORTS.contains(order)) {
            throw ApiException.validation(List.of(new FieldError("sort", "INVALID_SORT", "Неизвестная сортировка.")));
        }
        String query = q.strip();
        boolean filtered = !tags.isEmpty() || !order.isEmpty();
        if (filtered && sourceId == null && (!view.equals("works") || !tags.isEmpty())) throw ApiException.catalogSourceRequired();
        if (!tags.isEmpty() && !sources.getFirst().supportsTags()) {
            throw ApiException.problem(HttpStatus.UNPROCESSABLE_CONTENT, "CATALOG_OPTIONS_UNSUPPORTED",
                    "Источник или запрос не поддерживает выбранные фильтры и сортировку.");
        }
        if (view.equals("works")) {
            if (!tags.isEmpty()) localCatalog.validateTags(sourceId,tags);
            return works.list(sourceId,query,tags,order,
                    languages==null?List.of():languages.stream().distinct().sorted().toList(),limit,cursor,false);
        }
        return localCatalog.catalog(sourceId, query, tags, order,
                languages == null ? List.of() : languages.stream().distinct().sorted().toList(), limit, cursor);
    }

    @GetMapping("/sources/{sourceId}/tags")
    public Items<Tag> tags(@PathVariable String sourceId) {
        MangaSource adapter = connections.requireConnectedAdapter(sourceId);
        if (!adapter.supportsTags()) {
            throw ApiException.problem(HttpStatus.UNPROCESSABLE_CONTENT, "CATALOG_OPTIONS_UNSUPPORTED",
                    "Источник не поддерживает теги.");
        }
        return new Items<>(titleTags.dictionary(sourceId));
    }

    @GetMapping(TITLE_PATH)
    public JsonNode title(@PathVariable String sourceId, @PathVariable String externalTitleId) {
        checkExternalId("externalTitleId", externalTitleId);
        ApiException metadataError = null;
        SourceTitle title;
        try { title = source(sourceId).getTitle(externalTitleId); }
        catch (ApiException exception) {
            title = titles.find(sourceId, externalTitleId).orElseThrow(() -> exception);
            metadataError = exception;
        }
        var saved = titles.sync(List.of(title)).getFirst();
        var result = (tools.jackson.databind.node.ObjectNode) json.valueToTree(
                saved.withTags(titleTags.forTitle(sourceId, saved.externalTitleId())));
        result.set("chapterAvailability", sync.availability(sync.titleId(sourceId, externalTitleId),
                source(sourceId).supportedLanguages()));
        result.put("workId",works.forTitle(sync.titleId(sourceId,externalTitleId)).toString());
        if (metadataError != null) result.set("metadataError", json.valueToTree(
                java.util.Map.of("code", metadataError.code(), "detail", metadataError.getMessage(), "stage", "TITLE")));
        return result;
    }

    @GetMapping(TITLE_PATH + "/chapter-count")
    public JsonNode chapterCount(@PathVariable String sourceId, @PathVariable String externalTitleId) {
        checkExternalId("externalTitleId", externalTitleId);
        chapterListing.ensureTitle(sourceId, externalTitleId);
        var availability = sync.availability(sync.titleId(sourceId, externalTitleId), source(sourceId).supportedLanguages());
        var result = json.createObjectNode();
        result.set("chapterCount", availability.path("chapterCount"));
        result.set("availability", availability);
        return result;
    }

    @GetMapping(TITLE_PATH + "/chapters")
    public JsonNode chapters(@PathVariable String sourceId, @PathVariable String externalTitleId,
                             @RequestParam(required = false) String language,
                             @RequestParam(defaultValue = "100") int limit,
                             @RequestParam(required = false) String cursor) {
        checkExternalId("externalTitleId", externalTitleId);
        return chapterListing.listing(sourceId, externalTitleId, language, limit, cursor);
    }

    @GetMapping(TITLE_PATH + "/chapters/{chapterId}/pages")
    public JsonNode pages(@PathVariable String sourceId, @PathVariable String externalTitleId,
                              @PathVariable String chapterId, @RequestParam(required = false) String language,
                              @RequestParam(defaultValue = "false") boolean refresh) {
        checkExternalId("externalTitleId", externalTitleId);
        checkExternalId("chapterId", chapterId);
        ApiInputs.requireMatch("language", language, ApiInputs.LANGUAGE, 32);
        chapterListing.ensureTitle(sourceId, externalTitleId);
        return reader.pages(sourceId, externalTitleId, chapterId, language, refresh);
    }

    @GetMapping(TITLE_PATH + "/chapters/{chapterId}/images/{index}")
    public org.springframework.http.ResponseEntity<byte[]> image(
            @PathVariable String sourceId, @PathVariable String externalTitleId, @PathVariable String chapterId,
            @PathVariable int index, @RequestParam String language,
            @RequestParam(required = false) String manifestVersion) {
        checkExternalId("externalTitleId", externalTitleId);
        checkExternalId("chapterId", chapterId);
        ApiInputs.requireMatch("language", language, ApiInputs.LANGUAGE, 32);
        var image = reader.image(sourceId, externalTitleId, chapterId, language, index, manifestVersion);
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").contentType(image.type()).body(image.bytes());
    }

    private MangaSource source(String id) {
        return registry.require(ApiInputs.requireSourceId(id));
    }

    private void checkExternalId(String field, String id) {
        ApiInputs.requireMatch(field, id, ApiInputs.EXTERNAL_ID, 200);
    }
}
