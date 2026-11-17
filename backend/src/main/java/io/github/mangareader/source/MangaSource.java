package io.github.mangareader.source;

import io.github.mangareader.source.SourceModels.ChapterPages;
import io.github.mangareader.source.SourceModels.SourceChapter;
import io.github.mangareader.source.SourceModels.SourceTitle;
import io.github.mangareader.source.SourceModels.Tag;
import java.util.List;

public interface MangaSource {
    String id();
    String displayName();
    String websiteUrl();

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
