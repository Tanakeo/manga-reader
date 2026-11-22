package io.github.mangareader.source;

import io.github.mangareader.source.SourceModels.ChapterPages;
import io.github.mangareader.source.SourceModels.SourceChapter;
import io.github.mangareader.source.SourceModels.SourceTitle;
import io.github.mangareader.source.SourceModels.Tag;
import java.util.List;
import io.github.mangareader.source.SourceScan.CatalogPage;
import io.github.mangareader.source.SourceScan.ChapterResult;

public interface MangaSource {
    String id();
    String displayName();
    String websiteUrl();

    default List<String> supportedLanguages() { return List.of("ru", "en"); }
    default String policyVersion() { return "catalog-v1"; }
    default String incrementalCursor(java.time.Instant since) { return null; }

    default CatalogPage catalogPage(String cursor) {
        return new CatalogPage(searchTitles(""), null, null, "EXHAUSTED", "Single catalog");
    }

    default ChapterResult scanChapters(String externalTitleId) {
        var chapters = listChapters(externalTitleId);
        return new ChapterResult(chapters, true, chapters.size(), 0, "EXHAUSTED");
    }

    default void refreshManifest(String chapterId) {}

    default List<String> imageDomains() { return List.of(java.net.URI.create(websiteUrl()).getHost()); }

    default PublicSourceClient.Image serverImage(String title, String chapter, String language, int index) { return null; }
    default List<java.net.URI> alternativeImages(java.net.URI original) { return List.of(); }

    List<SourceTitle> searchTitles(String query);
    default List<SourceTitle> searchTitles(String query, List<String> tagIds, String sort) {
        return searchTitles(query);
    }
    default boolean supportsTags() {
        return false;
    }
    default List<Tag> catalogTags() {
        return List.of();
    }
    default List<String> catalogSorts() {
        return List.of();
    }
    SourceTitle getTitle(String externalTitleId);
    List<SourceChapter> listChapters(String externalTitleId);
    default int chapterCount(String externalTitleId) {
        return listChapters(externalTitleId).size();
    }
    ChapterPages getPages(String externalTitleId, String chapterId, String language);
}
