package io.github.mangareader.library;

import io.github.mangareader.library.LibraryModels.LibraryEntryView;
import io.github.mangareader.library.LibraryModels.Progress;
import io.github.mangareader.library.LibraryModels.UpdateEntry;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "library_entries")
public class LibraryEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "title_id", nullable = false, unique = true)
    private Title title;
    @Enumerated(EnumType.STRING)
    private ReadingStatus status;
    private String note;
    private Instant createdAt;
    private Instant updatedAt;
    private String progressChapterId;
    private String progressLanguage;
    private Integer progressPageIndex;

    protected LibraryEntry() {}

    LibraryEntry(Title title, ReadingStatus status, String note) {
        this.title = title;
        this.status = status;
        this.note = note;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    void apply(UpdateEntry update) {
        if (update.status() != null) {
            status = update.status();
        }
        if (update.hasNote()) {
            note = update.note();
        }
        if (update.hasProgress()) {
            Progress progress = update.progress();
            progressChapterId = progress == null ? null : progress.chapterId();
            progressLanguage = progress == null ? null : progress.language();
            progressPageIndex = progress == null ? null : progress.pageIndex();
        }
        updatedAt = Instant.now();
    }

    LibraryEntryView view() {
        Progress progress = progressChapterId == null ? null
                : new Progress(progressChapterId, progressLanguage, progressPageIndex);
        return new LibraryEntryView(id, title.summary(), status, note, progress, createdAt, updatedAt);
    }
}
