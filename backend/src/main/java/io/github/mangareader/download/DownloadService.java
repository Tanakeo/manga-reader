package io.github.mangareader.download;

import io.github.mangareader.download.DownloadFiles.PageFile;
import io.github.mangareader.download.DownloadModels.Download;
import io.github.mangareader.download.DownloadModels.DownloadError;
import io.github.mangareader.download.DownloadModels.Downloads;
import io.github.mangareader.download.DownloadModels.Manifest;
import io.github.mangareader.download.DownloadModels.Request;
import io.github.mangareader.error.ApiException;
import io.github.mangareader.error.ApiInputs;
import io.github.mangareader.library.TitleRepository;
import io.github.mangareader.source.MangaSourceRegistry;
import io.github.mangareader.source.SourceModels.ChapterPages;
import io.github.mangareader.source.SourceModels.Page;
import io.github.mangareader.source.SourceModels.SourceTitleSummary;
import io.github.mangareader.source.peppercarrot.PepperCarrotSource;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@DependsOnDatabaseInitialization
public class DownloadService {
    private static final Logger LOG = LoggerFactory.getLogger(DownloadService.class);
    private static final long MAX_CHAPTER_BYTES = 256L * 1024 * 1024;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final PepperCarrotSource source;
    private final MangaSourceRegistry sources;
    private final TitleRepository titles;
    private final DownloadFiles files;
    private final TransactionTemplate writes;

    DownloadService(JdbcClient jdbc, JsonMapper json, PepperCarrotSource source, MangaSourceRegistry sources,
                    TitleRepository titles, DownloadFiles files, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.source = source;
        this.sources = sources;
        this.titles = titles;
        this.files = files;
        this.writes = new TransactionTemplate(transactions);
    }

    @PostConstruct
    void recover() {
        jdbc.sql("""
                UPDATE chapter_downloads SET status = 'FAILED', error_code = 'DOWNLOAD_INTERRUPTED',
                    error = 'Загрузка прервалась. Повторите её.', updated_at = now()
                WHERE status = 'DOWNLOADING' RETURNING id
                """).query(UUID.class).list().forEach(this::clearFailed);
        var legacy = jdbc.sql("""
                SELECT * FROM chapter_downloads d WHERE status = 'READY'
                AND NOT EXISTS (SELECT 1 FROM download_pages p WHERE p.download_id = d.id)
                """).query((row, index) -> read(row)).list();
        for (Stored stored : legacy) {
            var id = stored.download().id();
            try {
                var pages = new ArrayList<PageFile>();
                for (Page page : stored.chapter().pages()) pages.add(files.describe(id, page.index()));
                writes.executeWithoutResult(transaction -> savePages(id, pages));
            } catch (IOException error) {
                markMissing(id, stored.attempt());
            }
        }
    }

    Downloads list(String sourceId, String externalTitleId) {
        boolean scoped = sourceId != null || externalTitleId != null;
        if (scoped) {
            ApiInputs.requireSourceId(sourceId);
            ApiInputs.requireMatch("externalTitleId", externalTitleId, ApiInputs.EXTERNAL_ID, 200);
        }
        var query = jdbc.sql("SELECT * FROM chapter_downloads "
                + (scoped ? "WHERE source_id = :source AND external_title_id = :title " : "")
                + "ORDER BY updated_at DESC, id");
        if (scoped) query.param("source", sourceId).param("title", externalTitleId);
        return new Downloads(!scoped || (source.id().equals(sourceId) && "pepper-and-carrot".equals(externalTitleId)),
                query.query((row, index) -> read(row).download()).list());
    }

    Download create(Request request) {
        var adapter = sources.require(ApiInputs.requireSourceId(request.sourceId()));
        ApiInputs.requireMatch("externalTitleId", request.externalTitleId(), ApiInputs.EXTERNAL_ID, 200);
        ApiInputs.requireMatch("chapterId", request.chapterId(), ApiInputs.EXTERNAL_ID, 200);
        ApiInputs.requireMatch("language", request.language(), ApiInputs.LANGUAGE, 32);
        if (!source.id().equals(adapter.id())) {
            throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "DOWNLOAD_UNSUPPORTED_SOURCE",
                    "Скачивание недоступно для этого источника.");
        }
        var title = source.getTitle(request.externalTitleId());
        return writes.execute(transaction -> {
            titles.sync(List.of(title));
            UUID titleId = jdbc.sql("SELECT id FROM titles WHERE source_id = :source AND external_title_id = :title")
                    .param("source", title.sourceId()).param("title", title.externalTitleId()).query(UUID.class).single();
            Download download = jdbc.sql("""
                    INSERT INTO chapter_downloads
                        (id, title_id, source_id, external_title_id, chapter_id, language, status, title_data)
                    VALUES (:id, :titleId, :source, :title, :chapter, :language, 'QUEUED', CAST(:data AS jsonb))
                    ON CONFLICT (title_id, chapter_id, language) DO UPDATE SET chapter_id = EXCLUDED.chapter_id
                    RETURNING *
                    """).param("id", UUID.randomUUID()).param("titleId", titleId).param("source", title.sourceId())
                    .param("title", title.externalTitleId()).param("chapter", request.chapterId())
                    .param("language", request.language()).param("data", json.writeValueAsString(withCover(title.summary(), null)))
                    .query((row, index) -> read(row).download()).single();
            if ("DELETING".equals(download.status())) throw deleting();
            return download;
        });
    }

    Download get(UUID id) {
        return require(id).download();
    }

    Download retry(UUID id) {
        return jdbc.sql("""
                UPDATE chapter_downloads SET status = 'QUEUED', error = NULL, error_code = NULL,
                    pages_done = 0, total_pages = NULL, updated_at = now()
                WHERE id = :id AND status = 'FAILED' RETURNING *
                """).param("id", id).query((row, index) -> read(row).download()).optional().orElseGet(() -> {
                    Download download = get(id);
                    if ("DELETING".equals(download.status())) throw deleting();
                    return download;
                });
    }

    Optional<Download> delete(UUID id) {
        var deleting = jdbc.sql("""
                UPDATE chapter_downloads SET status = 'DELETING', error = NULL, error_code = NULL, updated_at = now()
                WHERE id = :id AND status IN ('READY', 'FAILED', 'DELETING') RETURNING *
                """).param("id", id).query((row, index) -> read(row).download()).optional();
        if (deleting.isEmpty() && find(id).isPresent()) {
            throw problem(HttpStatus.CONFLICT, "DOWNLOAD_IN_PROGRESS", "Дождитесь завершения загрузки.");
        }
        return deleting;
    }

    Manifest manifest(UUID id) {
        Stored stored = ready(id);
        Download download = stored.download();
        List<Download> available = list(download.title().sourceId(), download.title().externalTitleId())
                .items().stream().filter(item -> "READY".equals(item.status()))
                .sorted(Comparator.comparing(Download::chapterNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Download::chapterId).thenComparing(Download::language)).toList();
        return new Manifest(download.title(), stored.chapter(), available);
    }

    ChapterPages pages(UUID id) {
        return ready(id).chapter();
    }

    DownloadFiles.StoredImage image(UUID id, int index) {
        var stored = ready(id);
        if (index < 0 || index >= stored.chapter().pageCount()) {
            throw problem(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "Страница не найдена.");
        }
        var page = jdbc.sql("""
                SELECT storage_key, mime_type, size_bytes, sha256 FROM download_pages
                WHERE download_id = :id AND page_index = :index
                """).param("id", id).param("index", index).query((row, position) ->
                        new PageFile(index, row.getString(1), row.getString(2), row.getLong(3), row.getString(4)))
                .optional();
        try {
            if (page.isPresent()) return files.image(page.get());
        } catch (IOException error) {
            LOG.debug("Could not read download {}", id, error);
        }
        markMissing(id, stored.attempt());
        throw missingFile();
    }

    public Optional<Integer> readyPageCount(SourceTitleSummary title, String chapter, String language) {
        return jdbc.sql("""
                SELECT total_pages FROM chapter_downloads WHERE source_id = :source AND external_title_id = :title
                    AND chapter_id = :chapter AND language = :language AND status = 'READY'
                """).param("source", title.sourceId()).param("title", title.externalTitleId())
                .param("chapter", chapter).param("language", language).query(Integer.class).optional();
    }

    @Scheduled(fixedDelay = 600)
    void processNext() {
        jdbc.sql("""
                SELECT id FROM chapter_downloads WHERE status = 'DELETING'
                AND (error_code IS NULL OR updated_at < now() - interval '5 seconds')
                ORDER BY updated_at, id LIMIT 1
                """).query(UUID.class).optional().ifPresent(this::cleanDelete);
        jdbc.sql("""
                UPDATE chapter_downloads SET status = 'DOWNLOADING', attempt_no = attempt_no + 1,
                    updated_at = now()
                WHERE id = (SELECT id FROM chapter_downloads WHERE status = 'QUEUED'
                            ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING *
                """).query((row, index) -> read(row)).optional().ifPresent(this::download);
    }

    private void download(Stored stored) {
        Download job = stored.download();
        UUID id = job.id();
        int attempt = stored.attempt();
        try {
            files.clear(id);
            jdbc.sql("DELETE FROM download_pages WHERE download_id = :id").param("id", id).update();
            ChapterPages chapter = source.getPages(job.title().externalTitleId(), job.chapterId(), job.language());
            jdbc.sql("""
                    UPDATE chapter_downloads SET total_pages = :total, chapter_title = :title,
                        chapter_number = :number, updated_at = now()
                    WHERE id = :id AND attempt_no = :attempt
                    """).param("total", chapter.pageCount()).param("title", chapter.title())
                    .param("number", Integer.parseInt(chapter.chapterId().substring(2, chapter.chapterId().indexOf('_'))))
                    .param("id", id).param("attempt", attempt).update();
            var pages = new ArrayList<Page>();
            var storedPages = new ArrayList<PageFile>();
            long totalBytes = 0;
            for (Page page : chapter.pages()) {
                if (page.index() != pages.size()) throw new IOException("Non-contiguous chapter pages");
                PageFile file = files.fetch(id, page.index(), page.imageUrl());
                totalBytes += file.sizeBytes();
                if (totalBytes > MAX_CHAPTER_BYTES) {
                    throw problem(HttpStatus.UNPROCESSABLE_CONTENT, "DOWNLOAD_TOO_LARGE", "Глава превышает лимит размера.");
                }
                storedPages.add(file);
                pages.add(new Page(page.index(), imageUrl(id, page.index())));
                jdbc.sql("UPDATE chapter_downloads SET pages_done = :done, updated_at = now() WHERE id = :id AND attempt_no = :attempt")
                        .param("done", pages.size()).param("id", id).param("attempt", attempt).update();
            }
            if (pages.isEmpty() || pages.size() != chapter.pageCount()) throw new IOException("Incomplete chapter");
            var local = new ChapterPages(chapter.chapterId(), chapter.title(), chapter.language(), chapter.pageCount(),
                    null, null, chapter.attribution(), List.copyOf(pages));
            files.publish(id);
            writes.executeWithoutResult(transaction -> {
                int updated = jdbc.sql("""
                        UPDATE chapter_downloads SET status = 'READY', title_data = CAST(:title AS jsonb),
                            chapter_data = CAST(:chapter AS jsonb), updated_at = now()
                        WHERE id = :id AND attempt_no = :attempt AND status = 'DOWNLOADING'
                        """).param("title", json.writeValueAsString(withCover(job.title(), pages.getFirst().imageUrl())))
                        .param("chapter", json.writeValueAsString(local)).param("id", id).param("attempt", attempt).update();
                if (updated == 0) throw new IllegalStateException("Download attempt changed");
                savePages(id, storedPages);
            });
        } catch (IOException | RuntimeException error) {
            String code = error instanceof ApiException api ? api.code() : "DOWNLOAD_FAILED";
            String message = error instanceof ApiException api ? api.getMessage() : "Не удалось сохранить файлы главы.";
            if (!(error instanceof ApiException)) LOG.warn("Could not save download {}", id, error);
            int failed = jdbc.sql("""
                    UPDATE chapter_downloads SET status = 'FAILED', error_code = :code, error = :detail, updated_at = now()
                    WHERE id = :id AND attempt_no = :attempt AND status = 'DOWNLOADING'
                    """).param("code", code).param("detail", message).param("id", id).param("attempt", attempt).update();
            if (failed == 1) clearFailed(id);
        }
    }

    private void savePages(UUID id, List<PageFile> pages) {
        for (PageFile page : pages) {
            jdbc.sql("""
                    INSERT INTO download_pages (download_id, page_index, storage_key, mime_type, size_bytes, sha256)
                    VALUES (:id, :index, :key, :mime, :bytes, :hash)
                    """).param("id", id).param("index", page.index()).param("key", page.storageKey())
                    .param("mime", page.mimeType()).param("bytes", page.sizeBytes()).param("hash", page.sha256()).update();
        }
    }

    private void cleanDelete(UUID id) {
        try {
            files.clear(id);
            jdbc.sql("DELETE FROM chapter_downloads WHERE id = :id AND status = 'DELETING'").param("id", id).update();
        } catch (IOException error) {
            jdbc.sql("""
                    UPDATE chapter_downloads SET error_code = 'DOWNLOAD_DELETE_FAILED',
                        error = 'Не удалось удалить файлы. Очистку можно повторить.', updated_at = now()
                    WHERE id = :id AND status = 'DELETING'
                    """).param("id", id).update();
        }
    }

    private Optional<Stored> find(UUID id) {
        return jdbc.sql("SELECT * FROM chapter_downloads WHERE id = :id").param("id", id)
                .query((row, index) -> read(row)).optional();
    }

    private Stored require(UUID id) {
        return find(id).orElseThrow(() ->
                problem(HttpStatus.NOT_FOUND, "DOWNLOAD_NOT_FOUND", "Локальная копия не найдена."));
    }

    private Stored ready(UUID id) {
        Stored stored = require(id);
        if (!"READY".equals(stored.download().status())) {
            if (stored.download().error() != null && "OFFLINE_FILE_MISSING".equals(stored.download().error().code())) {
                throw missingFile();
            }
            throw problem(HttpStatus.CONFLICT, "DOWNLOAD_NOT_READY", "Глава ещё не скачана.");
        }
        return stored;
    }

    private Stored read(ResultSet row) throws SQLException {
        String detail = row.getString("error");
        var download = new Download(row.getObject("id", UUID.class),
                json.readValue(row.getString("title_data"), SourceTitleSummary.class),
                row.getString("chapter_id"), row.getString("chapter_title"), row.getBigDecimal("chapter_number"),
                row.getString("language"), row.getString("status"), row.getInt("pages_done"),
                row.getObject("total_pages", Integer.class), detail == null ? null : new DownloadError(row.getString("error_code"), detail),
                row.getTimestamp("updated_at").toInstant());
        String chapter = row.getString("chapter_data");
        return new Stored(download, chapter == null ? null : json.readValue(chapter, ChapterPages.class),
                row.getInt("attempt_no"));
    }

    private void markMissing(UUID id, int attempt) {
        jdbc.sql("""
                UPDATE chapter_downloads SET status = 'FAILED', error_code = 'OFFLINE_FILE_MISSING',
                    error = 'Локальный файл отсутствует или повреждён. Повторите скачивание.', updated_at = now()
                WHERE id = :id AND attempt_no = :attempt AND status = 'READY'
                """).param("id", id).param("attempt", attempt).update();
    }

    private void clearFailed(UUID id) {
        try {
            files.clear(id);
        } catch (IOException error) {
            LOG.warn("Could not clean attempt {}", id, error);
        }
    }

    private static ApiException missingFile() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "OFFLINE_FILE_MISSING",
                "Локальный файл отсутствует или повреждён. Повторите скачивание.");
    }

    private static ApiException deleting() {
        return problem(HttpStatus.CONFLICT, "DOWNLOAD_DELETING", "Локальная копия удаляется.");
    }

    private static ApiException problem(HttpStatus status, String code, String message) {
        return ApiException.problem(status, code, message);
    }

    private static String imageUrl(UUID id, int index) {
        return "/api/v1/downloads/" + id + "/pages/" + index + "/image";
    }

    private static SourceTitleSummary withCover(SourceTitleSummary title, String coverUrl) {
        return new SourceTitleSummary(title.sourceId(), title.externalTitleId(), title.sourceName(), title.title(),
                title.author(), coverUrl, title.titleRu(), title.titleEn(), title.publicationStatus(), title.releaseYear());
    }

    private record Stored(Download download, ChapterPages chapter, int attempt) {}
}
