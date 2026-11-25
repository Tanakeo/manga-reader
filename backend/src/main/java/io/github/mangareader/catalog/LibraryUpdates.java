package io.github.mangareader.catalog;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/library-updates")
public class LibraryUpdates {
    private final JdbcClient jdbc;

    LibraryUpdates(JdbcClient jdbc) { this.jdbc = jdbc; }

    void observe(UUID titleId, String policy) {
        jdbc.sql("""
                INSERT INTO library_update_editions(title_id,chapter_id,language,chapter_title,published_at,announce)
                SELECT c.title_id,c.source_chapter_id,l.language,coalesce(c.data->>'title',c.source_chapter_id),
                    c.published_at,coalesce(b.policy_version=:policy,false)
                        AND EXISTS(SELECT 1 FROM library_entries e WHERE e.title_id=c.title_id)
                FROM source_chapters c CROSS JOIN LATERAL unnest(c.languages) l(language)
                LEFT JOIN library_update_baselines b ON b.title_id=c.title_id
                WHERE c.title_id=:title ON CONFLICT(title_id,chapter_id,language) DO NOTHING
                """).param("title",titleId).param("policy",policy).update();
        jdbc.sql("""
                INSERT INTO library_update_baselines VALUES(:title,:policy)
                ON CONFLICT(title_id) DO UPDATE SET policy_version=excluded.policy_version
                """).param("title",titleId).param("policy",policy).update();
    }

    @GetMapping
    Feed feed(@RequestParam(defaultValue="30") int limit, @RequestParam(required=false) Long before,
              @RequestParam(required=false) String language) {
        if (limit < 1 || limit > 100 || before != null && before < 1) {
            throw CatalogSyncService.invalid("page","Укажите корректную страницу ленты.");
        }
        if (language != null && !List.of("ru","en").contains(language)) {
            throw CatalogSyncService.invalid("language","Доступны ru и en.");
        }
        var filter = """
                FROM library_update_editions u JOIN library_entries e ON e.title_id=u.title_id
                JOIN titles t ON t.id=u.title_id
                LEFT JOIN chapter_read_state r ON r.title_id=u.title_id AND r.chapter_id=u.chapter_id AND r.language=u.language
                WHERE u.announce AND (:language='' OR u.language=:language)
                """;
        var rows = jdbc.sql("""
                SELECT u.*,t.source_id,t.external_title_id,t.title,t.title_ru,t.title_en,coalesce(r.is_read,false) AS is_read
                """ + filter + " AND u.id < :before ORDER BY u.id DESC LIMIT :limit")
                .param("language",language == null ? "" : language)
                .param("before",before == null ? Long.MAX_VALUE : before).param("limit",limit+1)
                .query((row,n) -> new Item(row.getLong("id"),row.getString("source_id"),row.getString("external_title_id"),
                        row.getString("title"),row.getString("title_ru"),row.getString("title_en"),row.getString("chapter_id"),
                        row.getString("chapter_title"),row.getString("language"),row.getBoolean("is_read"),
                        row.getObject("published_at",OffsetDateTime.class),row.getObject("discovered_at",OffsetDateTime.class))).list();
        long unread = jdbc.sql("SELECT count(*) " + filter + " AND NOT coalesce(r.is_read,false)")
                .param("language",language == null ? "" : language).query(Long.class).single();
        return new Feed(rows.stream().limit(limit).toList(),unread,rows.size() > limit ? rows.get(limit-1).id() : null);
    }

    record Item(long id,String sourceId,String externalTitleId,String title,String titleRu,String titleEn,String chapterId,
                String chapterTitle,String language,boolean isRead,OffsetDateTime publishedAt,OffsetDateTime discoveredAt) {}
    record Feed(List<Item> items,long unread,Long nextCursor) {}
}
