package io.github.mangareader.library;

import io.github.mangareader.source.SourceModels.SourceTitleSummary;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReadingModels {
    private ReadingModels() {}

    record Claim(UUID eventId, UUID sessionId, String expectedRevision) {}
    record Save(UUID eventId, UUID sessionId, long sequence, String expectedRevision, String chapterId,
                String language, int pageIndex, UUID downloadId, String manifestVersion,
                List<Integer> seenPageIndexes, boolean reachedEnd, int activeSecondsDelta) {}
    record Mark(UUID eventId, String expectedRevision, Boolean isRead) {}
    record Resume(String chapterId, String language, int pageIndex, UUID downloadId) {}
    record Chapter(String chapterId, String language, Integer pageIndex, Integer pageCount, Integer maxSeenPageIndex,
                   List<Integer> seenPageIndexes, boolean isRead, Instant completedAt, Instant lastReadAt,
                   long activeSeconds, String origin) {}
    record State(String revision, Resume resume, List<Chapter> chapters) {}
    record Session(UUID sessionId, String revision, Resume resume) {}
    record Saved(boolean applied, String revision, Resume resume, Chapter chapter) {}
    record HistoryItem(SourceTitleSummary title, String chapterId, String chapterTitle, String language,
                       int pageIndex, boolean isRead, Instant lastReadAt, String origin, UUID downloadId) {}
    record Page(boolean hasMore, String nextCursor) {}
    record History(List<HistoryItem> items, Page page) {}
}
