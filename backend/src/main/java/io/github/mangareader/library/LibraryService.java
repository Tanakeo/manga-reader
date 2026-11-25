package io.github.mangareader.library;

import io.github.mangareader.download.DownloadService;
import io.github.mangareader.error.ApiException;
import io.github.mangareader.library.LibraryModels.CreateEntry;
import io.github.mangareader.library.LibraryModels.LibraryEntryView;
import io.github.mangareader.library.LibraryModels.Progress;
import io.github.mangareader.library.LibraryModels.UpdateEntry;
import io.github.mangareader.source.MangaSourceRegistry;
import io.github.mangareader.source.SourceModels.ChapterPages;
import io.github.mangareader.source.SourceModels.SourceTitleSummary;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LibraryService {
    private final MangaSourceRegistry sources;
    private final TitleRepository titles;
    private final LibraryEntryRepository entries;
    private final EntityManager entityManager;
    private final TransactionTemplate writes;
    private final DownloadService downloads;
    private final ReadingService reading;

    public LibraryService(MangaSourceRegistry sources, TitleRepository titles, LibraryEntryRepository entries,
                          EntityManager entityManager, PlatformTransactionManager transactionManager,
                          DownloadService downloads, ReadingService reading) {
        this.sources = sources;
        this.titles = titles;
        this.entries = entries;
        this.entityManager = entityManager;
        this.writes = new TransactionTemplate(transactionManager);
        this.downloads = downloads;
        this.reading = reading;
    }

    LibraryEntryView create(CreateEntry request) {
        var title = sources.require(request.sourceId()).getTitle(request.externalTitleId());
        return writes.execute(transaction -> {
            titles.sync(List.of(title));
            UUID titleId = titles.lockTitleId(title.sourceId(), title.externalTitleId());
            if (entries.existsByTitleId(titleId)) {
                throw ApiException.problem(HttpStatus.CONFLICT, "DUPLICATE_LIBRARY_ENTRY",
                        "Этот тайтл уже есть в библиотеке.");
            }
            var entry = new LibraryEntry(entityManager.getReference(Title.class, titleId),
                    request.status(), request.note());
            return entries.saveAndFlush(entry).view(reading.head(titleId,false));
        });
    }

    @Transactional(readOnly = true)
    public List<LibraryEntryView> list() {
        var heads = reading.libraryHeads();
        return entries.findAllByOrderByUpdatedAtDescIdDesc().stream()
                .map(entry -> entry.view(heads.getOrDefault(entry.titleId(),ReadingService.Head.EMPTY))).toList();
    }

    @Transactional(readOnly = true)
    public LibraryEntryView get(UUID id) {
        var entry = entries.findOneById(id).orElseThrow(this::notFound);
        return entry.view(reading.head(entry.titleId(),false));
    }

    LibraryEntryView update(UUID id, UpdateEntry request) {
        if (request.progress() != null) {
            SourceTitleSummary title = get(id).title();
            validateProgress(title, request.progress());
        }
        return writes.execute(transaction -> {
            LibraryEntry entry = entries.findForUpdate(id).orElseThrow(this::notFound);
            entry.apply(request);
            var head = request.hasProgress() ? reading.manual(entry.titleId(),request.progress(),request.expectedReadingRevision())
                    : reading.head(entry.titleId(),false);
            return entry.view(head);
        });
    }

    public void delete(UUID id) {
        writes.executeWithoutResult(transaction -> {
            if (entries.deleteEntry(id) == 0) {
                throw notFound();
            }
        });
    }

    private void validateProgress(SourceTitleSummary title, Progress progress) {
        if (progress.pageIndex() < 0) {
            throw ApiException.invalidProgress("pageIndex", "Индекс страницы должен быть неотрицательным.");
        }
        var local = downloads.readyPageCount(title, progress.chapterId(), progress.language());
        if (local.isPresent()) {
            if (progress.pageIndex() >= local.get()) {
                throw ApiException.invalidProgress("pageIndex", "Страницы с таким индексом нет.");
            }
            return;
        }
        ChapterPages pages;
        try {
            pages = sources.require(title.sourceId()).getPages(title.externalTitleId(), progress.chapterId(),
                    progress.language());
        } catch (ApiException exception) {
            if (exception.code().equals("LANGUAGE_UNAVAILABLE")) {
                throw ApiException.invalidProgress("language", "У главы нет выбранного перевода.");
            }
            if (exception.code().equals("CHAPTER_NOT_FOUND") || exception.code().equals("TITLE_NOT_FOUND")) {
                throw ApiException.invalidProgress("chapterId", "Эта глава недоступна для тайтла.");
            }
            throw exception;
        }
        if (progress.pageIndex() >= pages.pageCount()) {
            throw ApiException.invalidProgress("pageIndex", "Страницы с таким индексом нет.");
        }
    }

    private ApiException notFound() {
        return ApiException.problem(HttpStatus.NOT_FOUND, "LIBRARY_ENTRY_NOT_FOUND", "Запись библиотеки не найдена.");
    }
}
