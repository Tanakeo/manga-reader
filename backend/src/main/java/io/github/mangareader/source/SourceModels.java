package io.github.mangareader.source;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SourceModels {
    private SourceModels() {}

    public record Items<T>(List<T> items) {}

    public record Source(String sourceId, String sourceName, String websiteUrl,
                         boolean supportsTags, List<String> sorts) {}

    public record Tag(String id, String group, Map<String, String> names, @JsonIgnore Integer version) {
        public Tag(String id, String group, Map<String, String> names) {
            this(id, group, names, null);
        }
    }

    public record Extension(String sourceId, String sourceName, String websiteUrl, boolean enabled) {}

    public record License(String code, String url) {}

    public record SourceTitleSummary(String sourceId, String externalTitleId, String sourceName,
                                     String title, String author, String coverUrl, String titleRu, String titleEn,
                                     String publicationStatus, Integer releaseYear) {}

    public record SourceTitle(String sourceId, String externalTitleId, String sourceName, String title,
                              String author, String coverUrl, String description, String sourceUrl,
                              License license, String titleRu, String titleEn, String publicationStatus,
                              Integer releaseYear, List<Tag> tags, @JsonIgnore Integer tagsVersion,
                              @JsonIgnore Instant tagsFetchedAt) {
        public SourceTitle(String sourceId, String externalTitleId, String sourceName, String title,
                           String author, String coverUrl, String description, String sourceUrl,
                           License license, String titleRu, String titleEn, String publicationStatus,
                           Integer releaseYear) {
            this(sourceId, externalTitleId, sourceName, title, author, coverUrl, description, sourceUrl,
                    license, titleRu, titleEn, publicationStatus, releaseYear, List.of(), null, null);
        }
        public SourceTitle withTags(List<Tag> savedTags) {
            return new SourceTitle(sourceId, externalTitleId, sourceName, title, author, coverUrl, description,
                    sourceUrl, license, titleRu, titleEn, publicationStatus, releaseYear, savedTags, null, null);
        }
        public SourceTitleSummary summary() {
            return new SourceTitleSummary(sourceId, externalTitleId, sourceName, title, author, coverUrl,
                    titleRu, titleEn, publicationStatus, releaseYear);
        }
    }

    public record ChapterCount(int chapterCount) {}

    public record SourceChapter(String id, BigDecimal number, String title, List<String> languages, Integer pageCount,
                                String logicalChapterId, String translationGroup, Instant publishedAt,
                                String volume, String numberRaw) {
        public SourceChapter(String id, BigDecimal number, String title, List<String> languages, Integer pageCount,
                             String logicalChapterId, String translationGroup, Instant publishedAt) {
            this(id,number,title,languages,pageCount,logicalChapterId,translationGroup,publishedAt,null,
                    number == null ? null : number.toPlainString());
        }
        public SourceChapter(String id, BigDecimal number, String title, List<String> languages, Integer pageCount) {
            this(id, number, title, languages, pageCount, id, null, null);
        }
    }

    public record Page(int index, String imageUrl) {}

    public record Attribution(String author, String creditsText, String sourceUrl, License license) {}

    public record ChapterPages(String chapterId, String title, String language, int pageCount,
                               String previousChapterId, String nextChapterId, Attribution attribution,
                               List<Page> pages) {}
}
