package io.github.mangareader.source;

import io.github.mangareader.source.SourceModels.SourceChapter;
import io.github.mangareader.source.SourceModels.SourceTitle;
import java.time.Instant;
import java.util.List;

public final class SourceScan {
    private SourceScan() {}

    public record CatalogPage(List<SourceTitle> items, String nextCursor, Long total,
                              String stopReason, String evidence) {}
    public record ChapterResult(List<SourceChapter> items, boolean complete, int rawCount,
                                int rejectedCount, String stopReason) {}
}
