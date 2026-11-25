package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.library.TitleRepository;
import io.github.mangareader.source.MangaSource;
import io.github.mangareader.source.MangaSourceRegistry;
import io.github.mangareader.source.SourceConnectionService;
import io.github.mangareader.source.SourceModels.SourceChapter;
import io.github.mangareader.source.SourceScan.ChapterResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@DependsOnDatabaseInitialization
public class CatalogSyncService {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogSyncService.class);
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MangaSourceRegistry registry;
    private final SourceConnectionService connections;
    private final TitleRepository titles;
    private final LibraryUpdates libraryUpdates;
    private final TransactionTemplate writes;
    private final java.util.concurrent.ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    private final Semaphore slots = new Semaphore(2);

    public CatalogSyncService(JdbcClient jdbc, JsonMapper json, MangaSourceRegistry registry,
                              SourceConnectionService connections, TitleRepository titles,
                              PlatformTransactionManager transactions, LibraryUpdates libraryUpdates) {
        this.jdbc = jdbc;
        this.json = json;
        this.registry = registry;
        this.connections = connections;
        this.titles = titles;
        this.libraryUpdates = libraryUpdates;
        this.writes = new TransactionTemplate(transactions);
    }

    @PostConstruct
    void initialize() {
        for (MangaSource source : registry.all()) {
            var old = jdbc.sql("SELECT policy_version FROM source_catalog_state WHERE source_id=:source")
                    .param("source", source.id()).query(String.class).optional();
            jdbc.sql("""
                    INSERT INTO source_catalog_state(source_id,languages,policy_version)
                    VALUES(:id,CAST(:languages AS text[]),:policy)
                    ON CONFLICT(source_id) DO UPDATE SET languages=excluded.languages,policy_version=excluded.policy_version
                    """).param("id", source.id()).param("languages", array(source.supportedLanguages()))
                    .param("policy", source.policyVersion()).update();
            if (old.isPresent() && !old.get().equals(source.policyVersion())) {
                jdbc.sql("DELETE FROM source_chapters WHERE title_id IN(SELECT id FROM titles WHERE source_id=:source)")
                        .param("source", source.id()).update();
                jdbc.sql("""
                        UPDATE title_chapter_checks SET state='UNVERIFIED',presence='UNKNOWN',exact_count=NULL,next_check_at=now()
                        WHERE title_id IN(SELECT id FROM titles WHERE source_id=:source)
                        """).param("source", source.id()).update();
                jdbc.sql("UPDATE source_catalog_state SET visibility_version=visibility_version+1 WHERE source_id=:source")
                        .param("source", source.id()).update();
            }
        }
    }

    @PreDestroy
    void close() { workers.shutdownNow(); }

    public JsonNode start(String sourceId, String mode) {
        connections.requireConnectedAdapter(sourceId);
        if (!"FULL".equals(mode) && !"INCREMENTAL".equals(mode)) throw invalid("mode", "Неверный режим синхронизации.");
        return writes.execute(transaction -> {
            jdbc.sql("SELECT source_id FROM source_catalog_state WHERE source_id=:source FOR UPDATE")
                    .param("source", sourceId).query(String.class).single();
            var active = jdbc.sql("""
                    SELECT id FROM catalog_sync_runs WHERE source_id=:source
                    AND state IN ('QUEUED','RUNNING','WAITING_RETRY','PAUSED')
                    """).param("source", sourceId).query(UUID.class).optional();
            if (active.isPresent()) return run(active.get());
            UUID id = UUID.randomUUID();
            String cursor = null;
            if ("INCREMENTAL".equals(mode)) {
                var baseline = jdbc.sql("""
                        SELECT max(started_at) FROM catalog_sync_runs WHERE source_id=:source
                        AND stop_reason='EXHAUSTED'
                        """).param("source", sourceId).query(java.time.OffsetDateTime.class).optional();
                if (baseline.isPresent()) cursor = registry.require(sourceId).incrementalCursor(baseline.get().toInstant());
            }
            jdbc.sql("INSERT INTO catalog_sync_runs(id,source_id,mode,state,cursor) VALUES(:id,:source,:mode,'QUEUED',:cursor)")
                    .param("id", id).param("source", sourceId).param("mode", mode).param("cursor", cursor).update();
            return run(id);
        });
    }

    public JsonNode status(String sourceId) {
        registry.require(sourceId);
        var object = json.createObjectNode();
        object.set("current", jdbc.sql("""
                SELECT id FROM catalog_sync_runs WHERE source_id=:source
                AND state IN ('QUEUED','RUNNING','WAITING_RETRY','PAUSED') ORDER BY started_at DESC LIMIT 1
                """).param("source", sourceId).query(UUID.class).optional().map(this::run).orElse(json.nullNode()));
        object.set("lastFinished", jdbc.sql("""
                SELECT id FROM catalog_sync_runs WHERE source_id=:source AND finished_at IS NOT NULL
                ORDER BY finished_at DESC LIMIT 1
                """).param("source", sourceId).query(UUID.class).optional().map(this::run).orElse(json.nullNode()));
        return object;
    }

    public JsonNode pause(String source, UUID id, boolean paused) {
        if (!paused) connections.requireConnectedAdapter(source);
        if (!paused && jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM catalog_sync_runs WHERE source_id=:source AND id<>:id
                    AND state IN ('QUEUED','RUNNING','WAITING_RETRY','PAUSED'))
                """).param("source",source).param("id",id).query(Boolean.class).single()) {
            throw ApiException.problem(HttpStatus.CONFLICT,"SYNC_ALREADY_RUNNING","У источника уже есть активная задача.");
        }
        int updated = jdbc.sql("""
                UPDATE catalog_sync_runs SET state=:state, lease_until=NULL, attempt_version=attempt_version+1,
                updated_at=now(),next_attempt_at=now(),finished_at=NULL,error=NULL,attempts=0,
                phase=CASE WHEN :resume AND phase='FINISHED' THEN 'CHAPTER_CHECKS' ELSE phase END
                WHERE id=:id AND source_id=:source AND
                    (finished_at IS NULL OR (:resume AND state IN ('FAILED','PARTIAL')))
                """).param("id", id).param("source", source).param("resume", !paused)
                .param("state", paused ? "PAUSED" : "QUEUED").update();
        if (updated == 0) throw ApiException.problem(HttpStatus.NOT_FOUND, "SYNC_RUN_NOT_FOUND", "Задача не найдена.");
        if (!paused) jdbc.sql("""
                UPDATE chapter_check_jobs j SET state='QUEUED',attempts=0,next_attempt_at=now()
                FROM titles t WHERE j.title_id=t.id AND t.source_id=:source AND j.state='ERROR'
                """).param("source", source).update();
        return run(id);
    }

    public UUID titleId(String source, String external) {
        return jdbc.sql("SELECT id FROM titles WHERE source_id=:source AND external_title_id=:external")
                .param("source", source).param("external", external).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.problem(HttpStatus.NOT_FOUND, "TITLE_NOT_FOUND", "Тайтл не найден."));
    }

    public JsonNode enqueue(String source, String external) {
        UUID id = titleId(source, external);
        UUID job = writes.execute(transaction -> queue(id, true, true));
        var response = json.createObjectNode().put("jobId", job.toString()).put("titleId", id.toString());
        response.set("availability", availability(id, registry.require(source).supportedLanguages()));
        return response;
    }

    UUID queue(UUID titleId, boolean explicit, boolean repeat) {
        return jdbc.sql("""
                INSERT INTO chapter_check_jobs(id,title_id,explicit_request) VALUES(:id,:title,:explicit)
                ON CONFLICT(title_id) DO UPDATE SET
                    explicit_request=chapter_check_jobs.explicit_request OR excluded.explicit_request,
                    state=CASE WHEN :repeat AND chapter_check_jobs.state IN ('DONE','ERROR')
                               THEN 'QUEUED' ELSE chapter_check_jobs.state END,
                    attempts=CASE WHEN :repeat AND chapter_check_jobs.state IN ('DONE','ERROR')
                                  THEN 0 ELSE chapter_check_jobs.attempts END,
                    next_attempt_at=CASE WHEN :repeat AND chapter_check_jobs.state IN ('DONE','ERROR')
                                        THEN now() ELSE chapter_check_jobs.next_attempt_at END
                RETURNING id
                """).param("id", UUID.randomUUID()).param("title", titleId).param("explicit", explicit)
                .param("repeat", repeat).query(UUID.class).single();
    }

    public JsonNode availability(UUID titleId, List<String> languages) {
        String value = jdbc.sql("SELECT catalog_availability(:id,CAST(:languages AS text[]))")
                .param("id", titleId).param("languages", array(languages)).query(String.class).single();
        return json.readTree(value);
    }

    @Scheduled(fixedDelay = 1000)
    void dispatch() {
        var candidates = jdbc.sql("""
                SELECT source_id FROM (
                    SELECT r.source_id,min(r.next_attempt_at) AS ready,0 AS urgent FROM catalog_sync_runs r
                    JOIN connected_sources c USING(source_id) WHERE r.phase='CATALOG'
                    AND r.state IN ('QUEUED','RUNNING','WAITING_RETRY') AND r.next_attempt_at<=now()
                    AND (r.lease_until IS NULL OR r.lease_until<now()) GROUP BY r.source_id
                    UNION ALL
                    SELECT t.source_id,min(j.next_attempt_at),max(j.explicit_request::int) FROM chapter_check_jobs j JOIN titles t ON t.id=j.title_id
                    WHERE j.state IN ('QUEUED','RUNNING','WAITING_RETRY') AND j.next_attempt_at<=now()
                    AND (j.lease_until IS NULL OR j.lease_until<now()) AND (j.explicit_request OR EXISTS(
                        SELECT 1 FROM connected_sources c WHERE c.source_id=t.source_id))
                    GROUP BY t.source_id
                    UNION ALL
                    SELECT source_id,min(updated_at),0 FROM catalog_sync_runs WHERE phase='CHAPTER_CHECKS'
                    AND state IN ('QUEUED','RUNNING','WAITING_RETRY') GROUP BY source_id
                ) ready GROUP BY source_id ORDER BY max(urgent) DESC,min(ready)
                """).query(String.class).list();
        for (String sourceId : candidates) {
            MangaSource source = registry.require(sourceId);
            if (!slots.tryAcquire()) break;
            if (!running.add(source.id())) { slots.release(); continue; }
            workers.execute(() -> {
                try { step(source); }
                catch (Exception error) { LOG.error("Catalog worker failed for {}", source.id(), error); }
                finally { running.remove(source.id()); slots.release(); }
            });
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    void recheckDueTitles() {
        jdbc.sql("""
                INSERT INTO chapter_check_jobs(id,title_id)
                SELECT gen_random_uuid(),e.title_id FROM library_entries e JOIN titles t ON t.id=e.title_id
                JOIN connected_sources s ON s.source_id=t.source_id
                ON CONFLICT(title_id) DO NOTHING
                """).update();
        jdbc.sql("""
                UPDATE chapter_check_jobs j SET state='QUEUED',attempts=0,next_attempt_at=now()
                WHERE j.state IN ('DONE','ERROR') AND EXISTS(
                    SELECT 1 FROM title_chapter_checks c JOIN titles t ON t.id=c.title_id
                    JOIN connected_sources s ON s.source_id=t.source_id
                    WHERE c.title_id=j.title_id AND c.next_check_at<=now())
                """).update();
    }

    private void step(MangaSource source) {
        var explicit = claimChapter(source.id(), true, false);
        if (explicit != null) { check(source, explicit); return; }
        boolean connected = jdbc.sql("SELECT EXISTS(SELECT 1 FROM connected_sources WHERE source_id=:source)")
                .param("source", source.id()).query(Boolean.class).single();
        if (!connected) return;
        var library = claimChapter(source.id(), false, true);
        if (library != null) { check(source, library); finishRuns(source.id()); return; }
        var run = claimRun(source.id());
        if (run != null) { importPage(source, run); return; }
        var chapter = claimChapter(source.id(), false, false);
        if (chapter != null) check(source, chapter);
        finishRuns(source.id());
    }

    private Claim claimRun(String source) {
        return jdbc.sql("""
                UPDATE catalog_sync_runs SET state='RUNNING',lease_until=now()+interval '2 minutes',
                    attempt_version=attempt_version+1,updated_at=now()
                WHERE id=(SELECT id FROM catalog_sync_runs WHERE source_id=:source AND phase='CATALOG'
                    AND state IN ('QUEUED','RUNNING','WAITING_RETRY') AND next_attempt_at<=now()
                    AND (lease_until IS NULL OR lease_until<now()) ORDER BY started_at LIMIT 1)
                RETURNING id,attempt_version,cursor,last_fingerprint,attempts
                """).param("source", source).query((r, i) -> new Claim(r.getObject(1, UUID.class), r.getLong(2),
                        r.getString(3), r.getString(4), r.getInt(5), null, null)).optional().orElse(null);
    }

    private Claim claimChapter(String source, boolean explicitOnly, boolean libraryOnly) {
        return jdbc.sql("""
                UPDATE chapter_check_jobs SET state='RUNNING',lease_until=now()+interval '2 minutes',
                    attempt_version=attempt_version+1,updated_at=now()
                WHERE id=(SELECT j.id FROM chapter_check_jobs j JOIN titles t ON t.id=j.title_id
                    WHERE t.source_id=:source AND (:explicit=false OR j.explicit_request)
                    AND (:library=false OR EXISTS(SELECT 1 FROM library_entries e WHERE e.title_id=t.id))
                    AND j.state IN ('QUEUED','RUNNING','WAITING_RETRY') AND j.next_attempt_at<=now()
                    AND (j.lease_until IS NULL OR j.lease_until<now())
                    ORDER BY j.explicit_request DESC,j.updated_at,j.id LIMIT 1)
                RETURNING id,attempt_version,title_id,attempts,
                    (SELECT external_title_id FROM titles WHERE id=chapter_check_jobs.title_id)
                """).param("source", source).param("explicit", explicitOnly).param("library", libraryOnly).query((r, i) ->
                        new Claim(r.getObject(1, UUID.class), r.getLong(2), null, null, r.getInt(4),
                                r.getObject(3, UUID.class), r.getString(5))).optional().orElse(null);
    }

    private void importPage(MangaSource source, Claim claim) {
        try {
            var page = source.catalogPage(claim.cursor());
            String fingerprint = hash(json.writeValueAsString(page.items().stream()
                    .map(item -> item.externalTitleId()).toList()));
            boolean repeated = fingerprint.equals(claim.fingerprint()) && page.nextCursor() != null;
            writes.executeWithoutResult(transaction -> {
                if (!owns("catalog_sync_runs", claim)) return;
                titles.syncCatalog(page.items());
                for (var title : page.items()) {
                    UUID id = titleId(source.id(), title.externalTitleId());
                    jdbc.sql("INSERT INTO catalog_sync_seen VALUES(:run,:title) ON CONFLICT DO NOTHING")
                            .param("run", claim.id()).param("title", id).update();
                    jdbc.sql("UPDATE titles SET last_seen_run_id=:run,last_seen_at=now() WHERE id=:title")
                            .param("run", claim.id()).param("title", id).update();
                    queue(id, false, true);
                }
                boolean exhausted = "EXHAUSTED".equals(page.stopReason());
                boolean partial = repeated || (page.nextCursor() == null && !exhausted);
                jdbc.sql("""
                        UPDATE catalog_sync_runs SET cursor=:cursor,pages_committed=pages_committed+1,
                        rows_seen=rows_seen+:rows,source_total=coalesce(source_total,:total),last_fingerprint=:fingerprint,
                        state=:state,phase=:phase,stop_reason=:reason,terminal_evidence=CAST(:evidence AS jsonb),
                        lease_until=NULL,attempts=0,error=NULL,next_attempt_at=now()+interval '1 second',updated_at=now(),
                        finished_at=CASE WHEN :partial THEN now() ELSE NULL END
                        WHERE id=:id AND attempt_version=:version
                        """).param("cursor", page.nextCursor()).param("rows", page.items().size()).param("total", page.total())
                        .param("fingerprint", fingerprint).param("state", partial ? "PARTIAL" : "QUEUED")
                        .param("phase", exhausted ? "CHAPTER_CHECKS" : "CATALOG")
                        .param("reason", repeated ? "REPEATED_PAGE" : page.stopReason())
                        .param("evidence", json.writeValueAsString(page.evidence())).param("partial", partial)
                        .param("id", claim.id()).param("version", claim.version()).update();
            });
        } catch (Exception exception) { fail("catalog_sync_runs", claim, source.id(), exception); }
    }

    private void check(MangaSource source, Claim claim) {
        try {
            writes.executeWithoutResult(transaction -> {
                if (!owns("chapter_check_jobs", claim)) return;
                for (String language : source.supportedLanguages()) {
                    boolean wasHidden = "NONE".equals(availability(claim.title(), List.of(language)).path("presence").asText());
                    jdbc.sql("""
                            INSERT INTO title_chapter_checks(title_id,language,policy_version,state,presence)
                            VALUES(:id,:language,:policy,'CHECKING','UNKNOWN')
                            ON CONFLICT(title_id,language) DO UPDATE SET state='CHECKING',presence='UNKNOWN',exact_count=NULL,error=NULL
                            """).param("id", claim.title()).param("language", language).param("policy", source.policyVersion()).update();
                    if (wasHidden) jdbc.sql("""
                            UPDATE source_catalog_state SET visibility_version=visibility_version+1 WHERE source_id=:source
                            """).param("source", source.id()).update();
                }
            });
            var result = source.scanChapters(claim.external());
            writes.executeWithoutResult(transaction -> {
                if (!owns("chapter_check_jobs", claim)) return;
                var before = source.supportedLanguages().stream()
                        .map(language -> availability(claim.title(), List.of(language)).path("presence").asText()).toList();
                if (result.complete()) jdbc.sql("DELETE FROM source_chapters WHERE title_id=:title")
                        .param("title", claim.title()).update();
                for (SourceChapter chapter : result.items()) {
                    jdbc.sql("""
                            INSERT INTO source_chapters VALUES(:title,:id,:logical,CAST(:languages AS text[]),
                            :published,CAST(:data AS jsonb))
                            ON CONFLICT(title_id,source_chapter_id) DO UPDATE SET logical_key=excluded.logical_key,
                            languages=excluded.languages,published_at=excluded.published_at,data=excluded.data
                            """).param("title", claim.title()).param("id", chapter.id())
                            .param("logical", chapter.logicalChapterId()).param("languages", array(chapter.languages()))
                            .param("published", chapter.publishedAt() == null ? null : chapter.publishedAt().atOffset(java.time.ZoneOffset.UTC))
                            .param("data", json.writeValueAsString(chapter)).update();
                }
                if (result.complete()) libraryUpdates.observe(claim.title(), source.policyVersion());
                for (String language : source.supportedLanguages()) saveCheck(source, claim.title(), language, result);
                jdbc.sql("""
                        UPDATE titles SET source_latest_chapter_at=
                            (SELECT max(published_at) FROM source_chapters WHERE title_id=:title) WHERE id=:title
                        """).param("title", claim.title()).update();
                boolean visibilityChanged = false;
                for (int i = 0; i < source.supportedLanguages().size(); i++) {
                    String after = availability(claim.title(), List.of(source.supportedLanguages().get(i)))
                            .path("presence").asText();
                    if (!after.equals(before.get(i)) && ("NONE".equals(after) || "NONE".equals(before.get(i)))) {
                        visibilityChanged = true;
                    }
                }
                if (visibilityChanged) jdbc.sql("""
                        UPDATE source_catalog_state SET visibility_version=visibility_version+1 WHERE source_id=:source
                        """).param("source", source.id()).update();
                jdbc.sql("""
                        UPDATE chapter_check_jobs SET state='DONE',lease_until=NULL,attempts=0,error=NULL,updated_at=now()
                        WHERE id=:id AND attempt_version=:version
                        """).param("id", claim.id()).param("version", claim.version()).update();
            });
        } catch (Exception exception) { fail("chapter_check_jobs", claim, source.id(), exception); }
    }

    private void saveCheck(MangaSource source, UUID title, String language, ChapterResult result) {
        long count = result.items().stream().filter(item -> item.languages().contains(language))
                .map(SourceChapter::logicalChapterId).distinct().count();
        jdbc.sql("""
                INSERT INTO title_chapter_checks(title_id,language,policy_version,state,presence,exact_count,
                known_count,checked_at,last_success_at,next_check_at,raw_count,rejected_count)
                VALUES(:title,:language,:policy,:state,:presence,:exact,:count,now(),now(),
                    now()+CASE WHEN :count=0 THEN interval '24 hours' ELSE interval '6 hours' END,:raw,:rejected)
                ON CONFLICT(title_id,language) DO UPDATE SET policy_version=excluded.policy_version,
                state=excluded.state,presence=excluded.presence,exact_count=excluded.exact_count,
                known_count=excluded.known_count,checked_at=now(),last_success_at=now(),
                next_check_at=excluded.next_check_at,raw_count=excluded.raw_count,rejected_count=excluded.rejected_count,error=NULL
                """).param("title", title).param("language", language).param("policy", source.policyVersion())
                .param("state", result.complete() ? "COMPLETE" : "PARTIAL")
                .param("presence", count > 0 ? "FOUND" : result.complete() ? "NONE" : "UNKNOWN")
                .param("exact", result.complete() ? count : null).param("count", count).param("raw", result.rawCount())
                .param("rejected", result.rejectedCount()).update();
    }

    private boolean owns(String table, Claim claim) {
        return jdbc.sql("SELECT attempt_version FROM " + table + " WHERE id=:id FOR UPDATE")
                .param("id", claim.id()).query(Long.class).optional().map(value -> value == claim.version()).orElse(false);
    }

    private void fail(String table, Claim claim, String source, Exception exception) {
        String cause = exception.getCause() == null ? "" : exception.getCause().toString();
        LOG.warn("{} {} operation failed at {}: {} ({})", source, table,
                claim.title() == null ? claim.cursor() : claim.external(), exception.toString(),
                cause.substring(0, Math.min(cause.length(), 500)));
        ApiException error = exception instanceof ApiException api ? api : ApiException.sourceBadResponse(exception);
        boolean retry = (error.code().equals("SOURCE_UNAVAILABLE") || error.code().equals("SOURCE_RATE_LIMITED")
                || error.code().equals("SOURCE_BAD_RESPONSE"))
                && claim.attempts() < 2;
        Object retryAfter = error.extensions().get("retryAfterSeconds");
        int requestedPause = retryAfter instanceof Number number ? number.intValue() : 0;
        int seconds = retry ? Math.min(3600, Math.max((int) Math.pow(2, claim.attempts() + 1), requestedPause)
                + java.util.concurrent.ThreadLocalRandom.current().nextInt(2)) : 0;
        var problem = json.createObjectNode().put("code", error.code()).put("stage", claim.title() == null ? "CATALOG" : "CHAPTERS")
                .put("detail", error.getMessage()).put("retryable", retry).put("retryAfterSeconds", seconds);
        writes.executeWithoutResult(transaction -> {
            if (!owns(table, claim)) return;
            jdbc.sql("UPDATE " + table + " SET state=:state,lease_until=NULL,attempts=attempts+1,"
                    + "next_attempt_at=now()+(:seconds * interval '1 second'),error=CAST(:error AS jsonb),updated_at=now()"
                    + (claim.title() == null ? ",finished_at=CASE WHEN :retry THEN NULL ELSE now() END,stop_reason=:reason" : "")
                    + " WHERE id=:id AND attempt_version=:version")
                    .param("state", retry ? "WAITING_RETRY" : claim.title() == null ? "FAILED" : "ERROR")
                    .param("seconds", seconds).param("error", json.writeValueAsString(problem)).param("id", claim.id())
                    .param("version", claim.version())
                    .params(claim.title() == null ? Map.of("retry", retry, "reason",
                            error.code().equals("SOURCE_ACCESS_DENIED") ? "ACCESS_DENIED" : "TEMPORARY_FAILURE") : Map.of())
                    .update();
            if (claim.title() != null) {
                for (String language : registry.require(source).supportedLanguages()) {
                    jdbc.sql("""
                            INSERT INTO title_chapter_checks(title_id,language,policy_version,state,presence,error,checked_at)
                            VALUES(:title,:language,'catalog-v1','ERROR','UNKNOWN',CAST(:error AS jsonb),now())
                            ON CONFLICT(title_id,language) DO UPDATE SET state='ERROR',presence='UNKNOWN',exact_count=NULL,
                            checked_at=now(),next_check_at=now()+interval '1 hour',error=excluded.error
                            """).param("title", claim.title()).param("language", language)
                            .param("error", json.writeValueAsString(problem)).update();
                }
            }
        });
    }

    private void finishRuns(String source) {
        jdbc.sql("""
                UPDATE catalog_sync_runs r SET state=CASE WHEN EXISTS(
                    SELECT 1 FROM catalog_sync_seen s JOIN chapter_check_jobs j ON j.title_id=s.title_id
                    WHERE s.run_id=r.id AND j.state='ERROR') THEN 'PARTIAL' ELSE 'COMPLETED' END,
                phase='FINISHED',updated_at=now(),finished_at=now(),lease_until=NULL
                WHERE source_id=:source AND phase='CHAPTER_CHECKS' AND state IN ('QUEUED','RUNNING','WAITING_RETRY')
                AND NOT EXISTS(SELECT 1 FROM catalog_sync_seen s JOIN chapter_check_jobs j ON j.title_id=s.title_id
                    WHERE s.run_id=r.id AND j.state IN ('QUEUED','RUNNING','WAITING_RETRY'))
                """).param("source", source).update();
    }

    private JsonNode run(UUID id) {
        return json.readTree(jdbc.sql("""
                SELECT jsonb_build_object('runId',r.id,'sourceId',source_id,'mode',mode,'state',state,'phase',phase,
                'pagesCommitted',pages_committed,'rowsSeen',rows_seen,'sourceTotal',source_total,'stopReason',stop_reason,
                'startedAt',started_at,'updatedAt',updated_at,'nextRetryAt',
                CASE WHEN state='WAITING_RETRY' THEN next_attempt_at END,'error',error,
                'uniqueTitlesSeen',(SELECT count(*) FROM catalog_sync_seen s WHERE s.run_id=r.id),
                'chaptersCheckedTitles',(SELECT count(*) FROM catalog_sync_seen s JOIN chapter_check_jobs j
                    ON j.title_id=s.title_id WHERE s.run_id=r.id AND j.state IN ('DONE','ERROR')),
                'terminalEvidence',terminal_evidence)
                FROM catalog_sync_runs r WHERE id=:id
                """).param("id", id).query(String.class).single());
    }

    static String array(List<String> values) {
        return "{" + String.join(",", values) + "}";
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    static ApiException invalid(String field, String detail) {
        return ApiException.validation(List.of(new ApiException.FieldError(field, "INVALID_VALUE", detail)));
    }

    private record Claim(UUID id, long version, String cursor, String fingerprint, int attempts, UUID title, String external) {}
}
