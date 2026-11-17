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

    public CatalogController(MangaSourceRegistry registry, SourceConnectionService connections, TitleRepository titles,
                             TitleTagRepository titleTags) {
        this.registry = registry;
        this.connections = connections;
        this.titles = titles;
        this.titleTags = titleTags;
    }

    @GetMapping("/catalog")
    public Items<SourceTitleSummary> catalog(@RequestParam(required = false) String sourceId,
                                             @RequestParam(defaultValue = "") String q,
                                             @RequestParam(name = "tagId", required = false) List<String> tagIds,
                                             @RequestParam(defaultValue = "") String sort) {
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
        if (filtered && sourceId == null) throw ApiException.catalogSourceRequired();
        if (filtered && ((!tags.isEmpty() && !sources.getFirst().supportsTags())
                || (!order.isEmpty() && !sources.getFirst().catalogSorts().contains(order)))) {
            throw ApiException.problem(HttpStatus.UNPROCESSABLE_CONTENT, "CATALOG_OPTIONS_UNSUPPORTED",
                    "Источник или запрос не поддерживает выбранные фильтры и сортировку.");
        }
        var found = new ArrayList<SourceTitle>();
        ApiException sourceFailure = null;
        boolean anySourceResponded = false;
        for (MangaSource source : sources) {
            try {
                found.addAll(filtered ? source.searchTitles(query, tags, order) : source.searchTitles(query));
                anySourceResponded = true;
            } catch (ApiException exception) {
                if (sourceId != null || !exception.status().is5xxServerError()) throw exception;
                if (sourceFailure == null) sourceFailure = exception;
            }
        }
        if (!anySourceResponded && sourceFailure != null) throw sourceFailure;
        return new Items<>(titles.syncCatalog(found).stream().map(SourceTitle::summary).toList());
    }

    @GetMapping("/sources/{sourceId}/tags")
    public Items<Tag> tags(@PathVariable String sourceId) {
        MangaSource adapter = connections.requireConnectedAdapter(sourceId);
        if (!adapter.supportsTags()) {
            throw ApiException.problem(HttpStatus.UNPROCESSABLE_CONTENT, "CATALOG_OPTIONS_UNSUPPORTED",
                    "Источник не поддерживает теги.");
        }
        return new Items<>(adapter.catalogTags());
    }

    @GetMapping(TITLE_PATH)
    public SourceTitle title(@PathVariable String sourceId, @PathVariable String externalTitleId) {
        checkExternalId("externalTitleId", externalTitleId);
        var title = source(sourceId).getTitle(externalTitleId);
        var saved = titles.sync(List.of(title)).getFirst();
        return saved.withTags(titleTags.forTitle(sourceId, saved.externalTitleId()));
    }

    @GetMapping(TITLE_PATH + "/chapter-count")
    public ChapterCount chapterCount(@PathVariable String sourceId, @PathVariable String externalTitleId) {
        checkExternalId("externalTitleId", externalTitleId);
        return new ChapterCount(source(sourceId).chapterCount(externalTitleId));
    }

    @GetMapping(TITLE_PATH + "/chapters")
    public Items<SourceChapter> chapters(@PathVariable String sourceId, @PathVariable String externalTitleId) {
        checkExternalId("externalTitleId", externalTitleId);
        return new Items<>(source(sourceId).listChapters(externalTitleId));
    }

    @GetMapping(TITLE_PATH + "/chapters/{chapterId}/pages")
    public ChapterPages pages(@PathVariable String sourceId, @PathVariable String externalTitleId,
                              @PathVariable String chapterId, @RequestParam(required = false) String language) {
        checkExternalId("externalTitleId", externalTitleId);
        checkExternalId("chapterId", chapterId);
        ApiInputs.requireMatch("language", language, ApiInputs.LANGUAGE, 32);
        return source(sourceId).getPages(externalTitleId, chapterId, language);
    }

    private MangaSource source(String id) {
        return registry.require(ApiInputs.requireSourceId(id));
    }

    private void checkExternalId(String field, String id) {
        ApiInputs.requireMatch(field, id, ApiInputs.EXTERNAL_ID, 200);
    }
}
