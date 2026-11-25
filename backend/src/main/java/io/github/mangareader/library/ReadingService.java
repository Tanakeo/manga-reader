package io.github.mangareader.library;

import static io.github.mangareader.library.ReadingModels.*;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.error.ApiInputs;
import io.github.mangareader.library.LibraryModels.Progress;
import io.github.mangareader.source.SourceModels.SourceTitleSummary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class ReadingService {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    ReadingService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    UUID title(String source, String external) {
        ApiInputs.requireSourceId(source);
        ApiInputs.requireMatch("externalTitleId", external, ApiInputs.EXTERNAL_ID, 200);
        return jdbc.sql("SELECT id FROM titles WHERE source_id=:source AND external_title_id=:external")
                .param("source", source).param("external", external).query(UUID.class).optional()
                .orElseThrow(() -> ApiException.problem(HttpStatus.NOT_FOUND,"TITLE_NOT_FOUND","Тайтл не найден."));
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public State state(UUID id) {
        var head = head(id, false);
        var chapters = jdbc.sql("SELECT * FROM chapter_read_state WHERE title_id=:id ORDER BY chapter_id,language")
                .param("id", id).query((row, n) -> chapter(row)).list();
        return new State(head.version(), resume(id, head), chapters);
    }

    Map<UUID, Head> libraryHeads() {
        var result = new HashMap<UUID, Head>();
        jdbc.sql("""
                SELECT r.* FROM title_reading_state r JOIN library_entries e ON e.title_id=r.title_id
                """).query((row, n) -> Map.entry(row.getObject("title_id", UUID.class), head(row)))
                .list().forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    @Transactional
    public Session claim(UUID id, Claim claim) {
        required(claim.eventId(), claim.sessionId());
        var head = lock(id);
        String hash = hash(List.of("claim", claim));
        if (!duplicate(id, claim.eventId(), hash)) {
            expect(head, claim.expectedRevision());
            jdbc.sql("""
                    UPDATE title_reading_state SET active_session=:session,last_sequence=0,revision=revision+1
                    WHERE title_id=:id
                    """).param("id", id).param("session", claim.sessionId()).update();
            head = head(id, false);
            receipt(id, claim.eventId(), hash, head);
        }
        if (!claim.sessionId().equals(head.session())) throw stale();
        return new Session(claim.sessionId(), head.version(), resume(id, head));
    }

    @Transactional
    public Saved save(UUID id, Save input) {
        required(input.eventId(), input.sessionId());
        validateKey(input.chapterId(), input.language());
        if (input.sequence() < 1 || input.pageIndex() < 0 || input.activeSecondsDelta() < 0
                || input.activeSecondsDelta() > 15 || input.seenPageIndexes() == null
                || input.seenPageIndexes().size() > 10000) throw invalid("position");
        var head = lock(id);
        var key = key(id, input.chapterId(), input.language());
        String hash = hash(List.of("save", input));
        if (duplicate(id, input.eventId(), hash)) return saved(id, head, key, false);
        if (!input.sessionId().equals(head.session()) || input.sequence() <= head.sequence()) throw stale();
        expect(head, input.expectedRevision());

        Context context = context(key, input);
        if (input.pageIndex() >= context.count() || input.seenPageIndexes().stream()
                .anyMatch(index -> index == null || index < 0 || index >= context.count())) throw invalid("pageIndex");

        var old = jdbc.sql("SELECT * FROM chapter_read_state WHERE title_id=:id AND chapter_id=:chapter AND language=:language")
                .params(key).query((row, n) -> new Previous(chapter(row), row.getString("layout_key"))).optional().orElse(null);
        var seen = new TreeSet<>(input.seenPageIndexes());
        seen.add(input.pageIndex());
        if (old != null && java.util.Objects.equals(old.chapter().pageCount(), context.count())
                && (old.layout() == null || context.layout() == null || old.layout().equals(context.layout()))) {
            seen.addAll(old.chapter().seenPageIndexes());
        }
        boolean complete = input.reachedEnd() && seen.size() == context.count();
        String layout = context.layout() != null ? context.layout() : old == null ? null : old.layout();
        jdbc.sql("""
                INSERT INTO chapter_read_state(title_id,chapter_id,language,last_page_index,page_count,seen_pages,
                    layout_key,is_read,completed_at,last_read_at,chapter_title,active_seconds,origin)
                VALUES(:id,:chapter,:language,:page,:count,CAST(:seen AS integer[]),:layout,:complete,
                    CASE WHEN :complete THEN now() END,now(),:label,:seconds,:origin)
                ON CONFLICT(title_id,chapter_id,language) DO UPDATE SET
                    last_page_index=excluded.last_page_index,page_count=excluded.page_count,
                    seen_pages=excluded.seen_pages,layout_key=excluded.layout_key,
                    is_read=chapter_read_state.is_read OR excluded.is_read,
                    completed_at=coalesce(chapter_read_state.completed_at,excluded.completed_at),
                    last_read_at=excluded.last_read_at,chapter_title=excluded.chapter_title,
                    active_seconds=chapter_read_state.active_seconds+excluded.active_seconds,origin=excluded.origin
                """).params(key).param("page",input.pageIndex()).param("count",context.count())
                .param("seen","{" + String.join(",",seen.stream().map(String::valueOf).toList()) + "}")
                .param("layout",layout).param("complete",complete).param("label",context.label())
                .param("seconds",input.activeSecondsDelta()).param("origin",input.downloadId() == null ? "ONLINE" : "OFFLINE").update();
        jdbc.sql("""
                UPDATE title_reading_state SET chapter_id=:chapter,language=:language,page_index=:page,
                    last_sequence=:sequence,revision=revision+1,updated_at=now() WHERE title_id=:id
                """).params(key).param("page",input.pageIndex()).param("sequence",input.sequence()).update();
        head = head(id, false);
        receipt(id, input.eventId(), hash, head);
        return saved(id, head, key, true);
    }

    @Transactional
    public Saved mark(UUID id, String chapter, String language, Mark input) {
        required(input.eventId(), input.isRead());
        validateKey(chapter, language);
        var head = lock(id);
        var key = key(id, chapter, language);
        String hash = hash(List.of("mark", chapter, language, input));
        if (duplicate(id, input.eventId(), hash)) return saved(id, head, key, false);
        expect(head, input.expectedRevision());
        boolean known = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM chapter_read_state WHERE title_id=:id AND chapter_id=:chapter AND language=:language
                UNION ALL SELECT 1 FROM source_chapters WHERE title_id=:id AND source_chapter_id=:chapter AND :language=ANY(languages)
                UNION ALL SELECT 1 FROM chapter_downloads WHERE title_id=:id AND chapter_id=:chapter AND language=:language AND status='READY'
                UNION ALL SELECT 1 FROM reader_manifests m JOIN titles t USING(source_id,external_title_id)
                    WHERE t.id=:id AND m.chapter_id=:chapter AND m.language=:language)
                """).params(key).query(Boolean.class).single();
        if (!known) throw invalid("chapterId");
        jdbc.sql("""
                INSERT INTO chapter_read_state(title_id,chapter_id,language,is_read,completed_at)
                VALUES(:id,:chapter,:language,:read,CASE WHEN :read THEN now() END)
                ON CONFLICT(title_id,chapter_id,language) DO UPDATE SET is_read=excluded.is_read,
                    completed_at=excluded.completed_at,
                    seen_pages=CASE WHEN :read THEN chapter_read_state.seen_pages ELSE '{}' END
                """).params(key).param("read",input.isRead()).update();
        invalidate(id, head.progress());
        head = head(id, false);
        receipt(id,input.eventId(),hash,head);
        return saved(id, head, key, true);
    }

    Head manual(UUID id, Progress progress, String expected) {
        var head = lock(id);
        expect(head,expected);
        if (progress != null) {
            jdbc.sql("""
                    INSERT INTO chapter_read_state(title_id,chapter_id,language,last_page_index)
                    VALUES(:id,:chapter,:language,:page)
                    ON CONFLICT(title_id,chapter_id,language) DO UPDATE SET last_page_index=excluded.last_page_index
                    """).params(key(id,progress.chapterId(),progress.language())).param("page",progress.pageIndex()).update();
        }
        invalidate(id, progress);
        return head(id, false);
    }

    private void invalidate(UUID id, Progress progress) {
        jdbc.sql("""
                UPDATE title_reading_state SET chapter_id=:chapter,language=:language,page_index=:page,
                    active_session=NULL,last_sequence=0,revision=revision+1,updated_at=now() WHERE title_id=:id
                """).param("id",id).param("chapter",progress == null ? null : progress.chapterId())
                .param("language",progress == null ? null : progress.language())
                .param("page",progress == null ? null : progress.pageIndex()).update();
    }

    History history(int limit, String cursor) {
        if (limit < 1 || limit > 100) throw invalid("limit");
        var params = new HashMap<String,Object>();
        params.put("limit",limit+1);
        String boundary = "";
        if (cursor != null) {
            try {
                String[] fields = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|",-1);
                if (fields.length != 4) throw new IllegalArgumentException();
                params.put("at",OffsetDateTime.parse(fields[0]));
                params.put("id",UUID.fromString(fields[1]));
                validateKey(fields[2],fields[3]);
                params.put("chapter",fields[2]);
                params.put("language",fields[3]);
                boundary = " AND (c.last_read_at,c.title_id,c.chapter_id,c.language)<(:at,:id,:chapter,:language)";
            } catch (IllegalArgumentException | java.time.DateTimeException error) { throw invalid("cursor"); }
        }
        var rows = jdbc.sql("""
                SELECT t.*,c.chapter_id,c.language,c.last_page_index,c.is_read,c.last_read_at,c.origin,
                    coalesce(c.chapter_title,s.data->>'title',c.chapter_id) AS chapter_title,d.id AS download_id
                FROM chapter_read_state c JOIN titles t ON t.id=c.title_id
                LEFT JOIN source_chapters s ON s.title_id=t.id AND s.source_chapter_id=c.chapter_id
                LEFT JOIN chapter_downloads d ON d.title_id=t.id AND d.chapter_id=c.chapter_id
                    AND d.language=c.language AND d.status='READY'
                WHERE c.last_read_at IS NOT NULL
                """ + boundary + " ORDER BY c.last_read_at DESC,c.title_id DESC,c.chapter_id DESC,c.language DESC LIMIT :limit")
                .params(params).query((row,n) -> {
                    var title = new SourceTitleSummary(row.getString("source_id"),row.getString("external_title_id"),
                            row.getString("source_name"),row.getString("title"),row.getString("author"),
                            row.getString("cover_url"),row.getString("title_ru"),row.getString("title_en"),
                            row.getString("publication_status"),row.getObject("release_year",Integer.class));
                    var item = new HistoryItem(title,row.getString("chapter_id"),row.getString("chapter_title"),
                            row.getString("language"),row.getInt("last_page_index"),row.getBoolean("is_read"),
                            instant(row,"last_read_at"),row.getString("origin"),row.getObject("download_id",UUID.class));
                    return Map.entry(row.getObject("id",UUID.class),item);
                }).list();
        boolean more = rows.size() > limit;
        String next = null;
        if (more) {
            var last = rows.get(limit-1);
            var item = last.getValue();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((item.lastReadAt() + "|" + last.getKey()
                    + "|" + item.chapterId() + "|" + item.language()).getBytes(StandardCharsets.UTF_8));
        }
        return new History(rows.stream().limit(limit).map(Map.Entry::getValue).toList(),new Page(more,next));
    }

    private Context context(Map<String,?> key, Save input) {
        if (input.downloadId() != null) {
            return jdbc.sql("""
                    SELECT total_pages,chapter_title FROM chapter_downloads WHERE title_id=:id AND chapter_id=:chapter
                      AND language=:language AND id=:download AND status='READY'
                    """).params(key).param("download",input.downloadId())
                    .query((row,n) -> new Context(row.getInt(1),row.getString(2),null)).optional().orElseThrow(this::missingContext);
        }
        return jdbc.sql("""
                SELECT m.data,m.version FROM reader_manifests m JOIN titles t USING(source_id,external_title_id)
                WHERE t.id=:id AND m.chapter_id=:chapter AND m.language=:language
                """).params(key).query((row,n) -> {
                    if (!row.getString("version").equals(input.manifestVersion())) {
                        throw conflict("READING_MANIFEST_CHANGED","Список страниц изменился. Обновите главу.");
                    }
                    var data = json.readTree(row.getString("data"));
                    var paths = new ArrayList<String>();
                    for (var page : data.path("pages")) {
                        paths.add(java.net.URI.create(page.path("imageUrl").asString()).getPath());
                    }
                    return new Context(data.path("pageCount").asInt(),data.path("title").asString(),hash(paths));
                }).optional().orElseThrow(this::missingContext);
    }

    private Saved saved(UUID id, Head head, Map<String,?> key, boolean applied) {
        var row = jdbc.sql("SELECT * FROM chapter_read_state WHERE title_id=:id AND chapter_id=:chapter AND language=:language")
                .params(key).query((r,n) -> chapter(r)).optional().orElse(null);
        return new Saved(applied,head.version(),resume(id,head),row);
    }

    private Resume resume(UUID id, Head head) {
        var position = head.progress();
        if (position == null) return null;
        UUID download = jdbc.sql("""
                SELECT id FROM chapter_downloads WHERE title_id=:id AND chapter_id=:chapter
                    AND language=:language AND status='READY'
                """).params(key(id,position.chapterId(),position.language())).query(UUID.class).optional().orElse(null);
        return new Resume(position.chapterId(),position.language(),position.pageIndex(),download);
    }

    private Head lock(UUID id) {
        jdbc.sql("INSERT INTO title_reading_state(title_id) VALUES(:id) ON CONFLICT DO NOTHING").param("id",id).update();
        return head(id,true);
    }

    Head head(UUID id, boolean lock) {
        return jdbc.sql("SELECT * FROM title_reading_state WHERE title_id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id",id).query((row,n) -> head(row)).optional().orElse(Head.EMPTY);
    }

    private static Head head(java.sql.ResultSet row) throws java.sql.SQLException {
        String chapter = row.getString("chapter_id");
        return new Head(row.getLong("revision"),row.getObject("active_session",UUID.class),row.getLong("last_sequence"),
                chapter == null ? null : new Progress(chapter,row.getString("language"),row.getInt("page_index")));
    }

    private static Chapter chapter(java.sql.ResultSet row) throws java.sql.SQLException {
        var pages = Arrays.asList((Integer[])row.getArray("seen_pages").getArray());
        return new Chapter(row.getString("chapter_id"),row.getString("language"),row.getObject("last_page_index",Integer.class),
                row.getObject("page_count",Integer.class),pages.isEmpty() ? null : pages.getLast(),pages,
                row.getBoolean("is_read"),instant(row,"completed_at"),instant(row,"last_read_at"),
                row.getLong("active_seconds"),row.getString("origin"));
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var time = row.getTimestamp(column);
        return time == null ? null : time.toInstant();
    }

    private boolean duplicate(UUID id, UUID event, String hash) {
        return jdbc.sql("SELECT title_id,request_hash FROM reading_events WHERE event_id=:event")
                .param("event",event).query((row,n) -> {
                    if (!id.equals(row.getObject(1,UUID.class)) || !hash.equals(row.getString(2))) {
                        throw conflict("READING_EVENT_CONFLICT","Идентификатор события уже использован.");
                    }
                    return true;
                }).optional().orElse(false);
    }

    private void receipt(UUID id, UUID event, String hash, Head head) {
        int inserted = jdbc.sql("""
                INSERT INTO reading_events(event_id,title_id,request_hash,revision) VALUES(:event,:id,:hash,:revision)
                ON CONFLICT DO NOTHING
                """).param("event",event).param("id",id).param("hash",hash).param("revision",head.revision()).update();
        if (inserted == 0) throw conflict("READING_EVENT_CONFLICT","Идентификатор события уже использован.");
    }

    @Scheduled(fixedDelay = 86400000)
    void pruneReceipts() {
        jdbc.sql("DELETE FROM reading_events WHERE created_at < now() - interval '30 days'").update();
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static Map<String,?> key(UUID id, String chapter, String language) {
        return Map.of("id",id,"chapter",chapter,"language",language);
    }

    private static void validateKey(String chapter, String language) {
        ApiInputs.requireMatch("chapterId",chapter,ApiInputs.EXTERNAL_ID,200);
        ApiInputs.requireMatch("language",language,ApiInputs.LANGUAGE,32);
    }

    private static void required(Object... values) {
        for (Object value : values) if (value == null) throw invalid("body");
    }

    private static ApiException invalid(String field) {
        return ApiException.validation(List.of(new ApiException.FieldError(field,"INVALID_VALUE","Проверьте данные чтения.")));
    }

    static void expect(Head head, String expected) {
        if (expected == null) throw ApiException.problem(HttpStatus.PRECONDITION_REQUIRED,
                "READING_REVISION_REQUIRED","Укажите версию позиции.");
        if (!head.version().equals(expected)) throw conflict("READING_CONFLICT","Позиция чтения уже обновилась.");
    }

    private static ApiException conflict(String code,String detail) {
        return ApiException.problem(HttpStatus.CONFLICT,code,detail);
    }

    private static ApiException stale() {
        return conflict("READING_SESSION_STALE","Другая вкладка сохраняет позицию чтения.");
    }

    private ApiException missingContext() {
        return conflict("READING_CONTEXT_REQUIRED","Сначала откройте страницы главы.");
    }

    record Head(long revision, UUID session, long sequence, Progress progress) {
        static final Head EMPTY = new Head(0,null,0,null);
        String version() { return Long.toString(revision); }
    }
    private record Context(int count,String label,String layout) {}
    private record Previous(Chapter chapter,String layout) {}
}
