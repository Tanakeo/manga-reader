package io.github.mangareader.library;

import io.github.mangareader.download.DownloadService;
import io.github.mangareader.download.DownloadModels;
import io.github.mangareader.error.ApiException;
import io.github.mangareader.error.ApiInputs;
import io.github.mangareader.library.LibraryModels.Progress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class LibraryActions {
    private final JdbcClient jdbc;
    private final ReadingService reading;
    private final DownloadService downloads;

    LibraryActions(JdbcClient jdbc, ReadingService reading, DownloadService downloads) {
        this.jdbc = jdbc;
        this.reading = reading;
        this.downloads = downloads;
    }

    @GetMapping("/sources/{source}/titles/{title}/bookmarks")
    List<Bookmark> bookmarks(@PathVariable String source,@PathVariable String title) {
        return jdbc.sql("SELECT * FROM chapter_bookmarks WHERE title_id=:id ORDER BY created_at DESC")
                .param("id",reading.title(source,title)).query((row,n) -> new Bookmark(row.getString("chapter_id"),
                        row.getString("language"),row.getObject("page_index",Integer.class))).list();
    }

    @PostMapping("/sources/{source}/titles/{title}/chapter-actions")
    List<ActionResult> batch(@PathVariable String source,@PathVariable String title,@RequestBody Batch request) {
        UUID titleId = reading.title(source,title);
        if (request.items() == null || request.items().isEmpty() || request.items().size()>100 || request.action()==null) {
            throw invalid("Выберите от 1 до 100 выпусков.");
        }
        var results = new ArrayList<ActionResult>();
        for (Bookmark item : request.items()) {
            try {
                validate(item);
                if (request.action().equals("download")) {
                    downloads.create(new DownloadModels.Request(source,title,item.chapterId(),item.language()));
                } else if (request.action().equals("read") || request.action().equals("unread")) {
                    var head = reading.head(titleId,false);
                    reading.mark(titleId,item.chapterId(),item.language(),new ReadingModels.Mark(
                            UUID.randomUUID(),head.version(),request.action().equals("read")));
                } else {
                    if (!request.action().equals("bookmark") && !request.action().equals("unbookmark")) {
                        throw invalid("Неизвестное действие.");
                    }
                    bookmark(titleId,item,request.action().equals("bookmark"));
                }
                results.add(new ActionResult(item,true,null,null));
            } catch (ApiException error) {
                results.add(new ActionResult(item,false,error.code(),error.getMessage()));
            }
        }
        return List.copyOf(results);
    }

    private void bookmark(UUID id,Bookmark item,boolean save) {
        var sql = jdbc.sql(save ? """
                INSERT INTO chapter_bookmarks(title_id,chapter_id,language,page_index)
                SELECT :id,:chapter,:language,:page WHERE EXISTS(
                  SELECT 1 FROM source_chapters WHERE title_id=:id AND source_chapter_id=:chapter AND :language=ANY(languages)
                  UNION ALL SELECT 1 FROM chapter_read_state WHERE title_id=:id AND chapter_id=:chapter AND language=:language
                  UNION ALL SELECT 1 FROM reader_manifests m JOIN titles t USING(source_id,external_title_id)
                       WHERE t.id=:id AND m.chapter_id=:chapter AND m.language=:language
                  UNION ALL SELECT 1 FROM chapter_downloads WHERE title_id=:id AND chapter_id=:chapter AND language=:language AND status='READY')
                ON CONFLICT(title_id,chapter_id,language) DO UPDATE SET page_index=excluded.page_index
                """ : """
                DELETE FROM chapter_bookmarks WHERE title_id=:id AND chapter_id=:chapter AND language=:language
                """).param("id",id).param("chapter",item.chapterId()).param("language",item.language());
        if (save) {
            if (item.pageIndex()!=null) {
                var count = jdbc.sql("""
                        SELECT max(n) FROM (
                          SELECT (m.data->>'pageCount')::integer AS n FROM reader_manifests m JOIN titles t USING(source_id,external_title_id)
                            WHERE t.id=:id AND m.chapter_id=:chapter AND m.language=:language
                          UNION ALL SELECT total_pages FROM chapter_downloads WHERE title_id=:id AND chapter_id=:chapter
                            AND language=:language AND status='READY'
                        ) bounds
                        """).param("id",id).param("chapter",item.chapterId()).param("language",item.language())
                        .query(Integer.class).optional();
                if (count.isEmpty() || item.pageIndex()>=count.get()) throw invalid("Страница закладки недоступна.");
            }
            if (sql.param("page",item.pageIndex()).update()==0) throw invalid("Глава закладки недоступна.");
        } else sql.update();
    }

    @GetMapping("/library/{entry}/transfer")
    Preview preview(@PathVariable UUID entry,@RequestParam String sourceId,@RequestParam String externalTitleId) {
        var origin = origin(entry,false);
        return preview(origin,reading.title(sourceId,externalTitleId));
    }

    @PostMapping("/library/{entry}/transfer")
    @Transactional
    Transfer transfer(@PathVariable UUID entry,@RequestBody Destination destination) {
        if (!Boolean.TRUE.equals(destination.confirmed())) throw invalid("Подтвердите соответствие тайтлов.");
        var origin = origin(entry,true);
        UUID target = reading.title(destination.sourceId(),destination.externalTitleId());
        UUID previous = jdbc.sql("SELECT target_entry_id FROM library_transfers WHERE origin_entry_id=:entry AND target_title_id=:target")
                .param("entry",entry).param("target",target).query(UUID.class).optional().orElse(null);
        if (previous!=null) return new Transfer(previous,true);
        var preview = preview(origin,target);
        if (preview.targetInLibrary()) throw ApiException.problem(HttpStatus.CONFLICT,"TRANSFER_TARGET_EXISTS","Тайтл назначения уже в библиотеке.");
        UUID installed = UUID.randomUUID();
        int created = jdbc.sql("""
                INSERT INTO library_entries(id,title_id,status,note,created_at,updated_at)
                SELECT :new,:target,status,note,now(),now() FROM library_entries WHERE id=:origin
                ON CONFLICT(title_id) DO NOTHING
                """).param("new",installed).param("target",target).param("origin",entry).update();
        if (created==0) throw ApiException.problem(HttpStatus.CONFLICT,"TRANSFER_TARGET_EXISTS","Тайтл назначения уже в библиотеке.");
        jdbc.sql("""
                INSERT INTO library_section_entries(section_id,entry_id)
                SELECT section_id,:new FROM library_section_entries WHERE entry_id=:origin
                """).param("new",installed).param("origin",entry).update();
        if (preview.progress()!=null) {
            // Keep target reading state if the reader already has independent history there.
            jdbc.sql("""
                    INSERT INTO title_reading_state(title_id,chapter_id,language,page_index)
                    VALUES(:target,:chapter,:language,0) ON CONFLICT DO NOTHING
                    """).param("target",target).param("chapter",preview.progress().chapterId())
                    .param("language",preview.progress().language()).update();
        }
        jdbc.sql("INSERT INTO library_transfers VALUES(:origin,:target,:new)")
                .param("origin",entry).param("target",target).param("new",installed).update();
        return new Transfer(installed,false);
    }

    private Origin origin(UUID id,boolean lock) {
        return jdbc.sql("SELECT title_id,note,status FROM library_entries WHERE id=:id" + (lock ? " FOR UPDATE" : ""))
                .param("id",id).query((row,n) -> new Origin(row.getObject(1,UUID.class),row.getString(2),row.getString(3)))
                .optional().orElseThrow(() -> ApiException.problem(HttpStatus.NOT_FOUND,"LIBRARY_ENTRY_NOT_FOUND","Запись не найдена."));
    }

    private Preview preview(Origin origin,UUID target) {
        if (origin.title().equals(target)) throw invalid("Выберите другой источник.");
        boolean inLibrary=jdbc.sql("SELECT EXISTS(SELECT 1 FROM library_entries WHERE title_id=:id)")
                .param("id",target).query(Boolean.class).single();
        if (jdbc.sql("SELECT a.source_id=b.source_id FROM titles a,titles b WHERE a.id=:a AND b.id=:b")
                .param("a",origin.title()).param("b",target).query(Boolean.class).single()) throw invalid("Выберите тайтл другого источника.");
        var current = reading.head(origin.title(),false).progress();
        Progress candidate = current == null ? null : jdbc.sql("""
                SELECT b.source_chapter_id FROM source_chapters a JOIN source_chapters b ON b.title_id=:target
                 AND b.data->'number'=a.data->'number' AND b.data->>'volume' IS NOT DISTINCT FROM a.data->>'volume'
                WHERE a.title_id=:origin AND a.source_chapter_id=:chapter AND jsonb_typeof(a.data->'number')='number'
                    AND :language=ANY(a.languages) AND :language=ANY(b.languages)
                LIMIT 2
                """).param("target",target).param("origin",origin.title()).param("chapter",current.chapterId())
                .param("language",current.language()).query(String.class).list().stream()
                .collect(java.util.stream.Collectors.collectingAndThen(java.util.stream.Collectors.toList(),
                  matches -> matches.size()==1 ? new Progress(matches.getFirst(),current.language(),0) : null));
        return new Preview(origin.status(),origin.note(),candidate,inLibrary);
    }

    private static void validate(Bookmark item) {
        if (item==null || item.pageIndex()!=null && item.pageIndex()<0) throw invalid("Некорректная закладка.");
        ApiInputs.requireMatch("chapterId",item.chapterId(),ApiInputs.EXTERNAL_ID,200);
        ApiInputs.requireMatch("language",item.language(),ApiInputs.LANGUAGE,32);
    }
    private static ApiException invalid(String detail) {
        return ApiException.problem(HttpStatus.UNPROCESSABLE_CONTENT,"LIBRARY_ACTION_INVALID",detail);
    }

    record Bookmark(String chapterId,String language,Integer pageIndex) {}
    record Batch(String action,List<Bookmark> items) {}
    record ActionResult(Bookmark item,boolean success,String code,String detail) {}
    record Destination(String sourceId,String externalTitleId,Boolean confirmed) {}
    record Origin(UUID title,String note,String status) {}
    record Preview(String status,String note,Progress progress,boolean targetInLibrary) {}
    record Transfer(UUID entryId,boolean duplicate) {}
}
