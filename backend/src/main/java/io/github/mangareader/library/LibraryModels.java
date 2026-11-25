package io.github.mangareader.library;

import io.github.mangareader.source.SourceModels.SourceTitleSummary;
import java.time.Instant;
import java.util.UUID;

public final class LibraryModels {
    private LibraryModels() {}

    public record Progress(String chapterId, String language, int pageIndex) {}

    public record LibraryEntryView(UUID id, SourceTitleSummary title, ReadingStatus status, String note,
                                   Progress progress, String readingRevision, Instant createdAt, Instant updatedAt) {}

    record CreateEntry(String sourceId, String externalTitleId, ReadingStatus status, String note) {}

    record UpdateEntry(ReadingStatus status, boolean hasNote, String note,
                       boolean hasProgress, Progress progress, String expectedReadingRevision) {}
}
