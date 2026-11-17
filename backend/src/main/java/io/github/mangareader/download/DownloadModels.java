package io.github.mangareader.download;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.mangareader.source.SourceModels.ChapterPages;
import io.github.mangareader.source.SourceModels.SourceTitleSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DownloadModels {
    private DownloadModels() {}

    public record Request(String sourceId, String externalTitleId, String chapterId, String language) {}

    public record DownloadError(String code, String detail) {}

    public record Download(UUID id, SourceTitleSummary title, String chapterId, String chapterTitle,
                           BigDecimal chapterNumber, String language, String status, int pagesDone,
                           Integer totalPages, DownloadError error, Instant updatedAt) {
        @JsonProperty("downloadedPages")
        public int downloadedPages() { return pagesDone; }
        @JsonProperty("pageCount")
        public Integer pageCount() { return totalPages; }
    }

    public record Downloads(boolean supported, List<Download> items) {}

    public record Manifest(SourceTitleSummary title, ChapterPages chapter, List<Download> available) {}
}
