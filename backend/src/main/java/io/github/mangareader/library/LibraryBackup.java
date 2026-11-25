package io.github.mangareader.library;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.error.ApiInputs;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/library-backup")
class LibraryBackup {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    LibraryBackup(JdbcClient jdbc, JsonMapper json) {
        this.jdbc=jdbc;
        this.json=json;
    }

    @GetMapping
    JsonNode exportBackup() {
        return json.readTree(jdbc.sql("""
                WITH seeds AS (
                  SELECT title_id FROM library_entries UNION SELECT title_id FROM title_reading_state
                  UNION SELECT title_id FROM chapter_read_state UNION SELECT title_id FROM chapter_bookmarks
                ), related AS (
                  SELECT t.id AS title_id FROM titles t WHERE t.work_id IN (SELECT x.work_id FROM titles x JOIN seeds s ON s.title_id=x.id)
                )
                SELECT jsonb_build_object(
                  'version',2,
                  'works',coalesce((SELECT jsonb_agg(to_jsonb(w)) FROM works w WHERE EXISTS(SELECT 1 FROM titles t JOIN related r ON r.title_id=t.id WHERE t.work_id=w.id)),'[]'),
                  'workPreferences',coalesce((SELECT jsonb_agg(to_jsonb(p)) FROM work_source_preferences p WHERE EXISTS(SELECT 1 FROM titles t JOIN related r ON r.title_id=t.id WHERE t.work_id=p.work_id)),'[]'),
                  'matchDecisions',coalesce((SELECT jsonb_agg(to_jsonb(c)) FROM work_match_candidates c WHERE state<>'PENDING' AND left_title IN(SELECT title_id FROM related) AND right_title IN(SELECT title_id FROM related)),'[]'),
                  'titleAliases',coalesce((SELECT jsonb_agg(to_jsonb(a)) FROM title_aliases a WHERE title_id IN(SELECT title_id FROM related)),'[]'),
                  'titleIdentifiers',coalesce((SELECT jsonb_agg(to_jsonb(a)) FROM title_external_identifiers a WHERE title_id IN(SELECT title_id FROM related)),'[]'),
                  'titles',coalesce((SELECT jsonb_agg(to_jsonb(t)-'search_document') FROM titles t JOIN related r ON r.title_id=t.id),'[]'),
                  'entries',coalesce((SELECT jsonb_agg(to_jsonb(e)-ARRAY['progress_chapter_id','progress_language','progress_page_index']) FROM library_entries e),'[]'),
                  'sections',coalesce((SELECT jsonb_agg(to_jsonb(s)) FROM library_sections s),'[]'),
                  'memberships',coalesce((SELECT jsonb_agg(to_jsonb(s)) FROM library_section_entries s),'[]'),
                  'positions',coalesce((SELECT jsonb_agg(to_jsonb(p)-ARRAY['active_session','last_sequence','revision']) FROM title_reading_state p WHERE chapter_id IS NOT NULL),'[]'),
                  'chapters',coalesce((SELECT jsonb_agg(to_jsonb(c)) FROM chapter_read_state c),'[]'),
                  'bookmarks',coalesce((SELECT jsonb_agg(to_jsonb(b)) FROM chapter_bookmarks b),'[]'))
                """).query(String.class).single());
    }

    @PostMapping("/preview")
    Preview preview(@RequestBody JsonNode backup) {
        validate(backup);
        int conflicts = jdbc.sql("""
                SELECT count(*) FROM jsonb_to_recordset(CAST(:titles AS jsonb)) AS src(source_id text,external_title_id text,id uuid)
                JOIN titles t USING(source_id,external_title_id) JOIN library_entries e ON e.title_id=t.id
                JOIN jsonb_to_recordset(CAST(:entries AS jsonb)) AS backup(title_id uuid) ON backup.title_id=src.id
                """).param("titles",backup.path("titles").toString()).param("entries",backup.path("entries").toString()).query(Integer.class).single();
        return new Preview(backup.path("entries").size(),backup.path("sections").size(),backup.path("chapters").size(),
                backup.path("bookmarks").size(),conflicts);
    }

    @PostMapping
    @Transactional
    Preview restore(@RequestBody JsonNode backup) {
        var result=preview(backup);
        var inserted=jdbc.sql("""
                INSERT INTO titles(id,source_id,external_title_id,source_name,title,source_url,author,description,cover_url,
                                   license_code,license_url,title_ru,title_en,publication_status,release_year,identity_metadata)
                SELECT gen_random_uuid(),source_id,external_title_id,source_name,title,source_url,author,description,cover_url,
                       license_code,license_url,title_ru,title_en,publication_status,release_year,identity_metadata
                FROM jsonb_populate_recordset(NULL::titles,CAST(:data AS jsonb))
                ON CONFLICT(source_id,external_title_id) DO NOTHING
                RETURNING id
                """).param("data",backup.path("titles").toString()).query(java.util.UUID.class).list();
        restoreWorks(backup,inserted);
        restore(backup,"entries","library_entries","id,title_id,status,note,created_at,updated_at",
                "gen_random_uuid(),mapping.target,b.status,b.note,b.created_at,b.updated_at");
        jdbc.sql("""
                INSERT INTO library_sections(id,name,normalized_name,created_at,system_key)
                SELECT gen_random_uuid(),name,normalized_name,created_at,system_key
                FROM jsonb_populate_recordset(NULL::library_sections,CAST(:data AS jsonb))
                ON CONFLICT DO NOTHING
                """).param("data",backup.path("sections").toString()).update();
        jdbc.sql(mapping() + """
                INSERT INTO library_section_entries(section_id,entry_id)
                SELECT s.id,e.id FROM jsonb_populate_recordset(NULL::library_section_entries,CAST(:data AS jsonb)) b
                JOIN jsonb_populate_recordset(NULL::library_entries,CAST(:entries AS jsonb)) old ON old.id=b.entry_id
                JOIN mapping ON mapping.origin=old.title_id JOIN library_entries e ON e.title_id=mapping.target
                JOIN jsonb_populate_recordset(NULL::library_sections,CAST(:sections AS jsonb)) sector ON sector.id=b.section_id
                JOIN library_sections s ON (sector.system_key IS NOT NULL AND s.system_key=sector.system_key)
                    OR (sector.system_key IS NULL AND s.system_key IS NULL AND s.normalized_name=sector.normalized_name)
                ON CONFLICT DO NOTHING
                """).param("titles",backup.path("titles").toString()).param("data",backup.path("memberships").toString())
                .param("entries",backup.path("entries").toString()).param("sections",backup.path("sections").toString()).update();
        restore(backup,"positions","title_reading_state","title_id,chapter_id,language,page_index,updated_at",
                "mapping.target,b.chapter_id,b.language,b.page_index,b.updated_at");
        restore(backup,"chapters","chapter_read_state",
                "title_id,chapter_id,language,last_page_index,page_count,seen_pages,layout_key,is_read,completed_at,last_read_at,chapter_title,active_seconds,origin",
                "mapping.target,b.chapter_id,b.language,b.last_page_index,b.page_count,b.seen_pages,b.layout_key,b.is_read,b.completed_at,b.last_read_at,b.chapter_title,b.active_seconds,b.origin");
        restore(backup,"bookmarks","chapter_bookmarks","title_id,chapter_id,language,page_index,created_at",
                "mapping.target,b.chapter_id,b.language,b.page_index,b.created_at");
        return result;
    }

    private void restore(JsonNode backup,String field,String table,String columns,String projection) {
        jdbc.sql(mapping() + "INSERT INTO " + table + "(" + columns + ") SELECT " + projection
                + " FROM jsonb_populate_recordset(NULL::" + table + ",CAST(:data AS jsonb)) b"
                + " JOIN mapping ON mapping.origin=b.title_id ON CONFLICT DO NOTHING")
                .param("titles",backup.path("titles").toString()).param("data",backup.path(field).toString()).update();
    }

    private static String mapping() {
        return """
                WITH mapping AS (
                    SELECT src.id AS origin,t.id AS target
                    FROM jsonb_to_recordset(CAST(:titles AS jsonb)) AS src(id uuid,source_id text,external_title_id text)
                    JOIN titles t USING(source_id,external_title_id)
                )
                """;
    }

    private static void validate(JsonNode data) {
        try {
            if (!data.isObject() || !data.path("version").isIntegralNumber() || !List.of(1,2).contains(data.path("version").asInt())
                    || data.toString().length()>8_000_000) throw new IllegalArgumentException();
            for (String field : List.of("titles","entries","sections","memberships","positions","chapters","bookmarks")) {
                var items=data.path(field);
                if (!items.isArray() || items.size()>20000) throw new IllegalArgumentException();
                for (var item : items) if (!item.isObject()) throw new IllegalArgumentException();
            }
            if(data.path("version").asInt()==2) {
                for(String field:List.of("works","workPreferences","matchDecisions","titleAliases","titleIdentifiers")) {
                    if(!data.path(field).isArray() || data.path(field).size()>20000) throw new IllegalArgumentException();
                    for(var item:data.path(field)) if(!item.isObject()) throw new IllegalArgumentException();
                }
            }
            for (var title : data.path("titles")) {
                String source=title.path("source_id").asString();
                ApiInputs.requireSourceId(source);
                ApiInputs.requireMatch("externalTitleId",title.path("external_title_id").asString(),ApiInputs.EXTERNAL_ID,200);
                java.util.UUID.fromString(title.path("id").asString());
                if(data.path("version").asInt()==2) java.util.UUID.fromString(title.path("work_id").asString());
                for (String key : List.of("source_url","cover_url","license_url")) {
                    var field=title.path(key);
                    if (field.isMissingNode() || field.isNull()) continue;
                    var url=java.net.URI.create(field.asString());
                    String titleRoute = "/catalog/" + source + "/" + title.path("external_title_id").asString();
                    if (field.asString().startsWith("/api/v1/sources/") || field.asString().equals(titleRoute)) continue;
                    if (!List.of("https","http").contains(url.getScheme()==null ? "" : url.getScheme())
                            || url.getHost()==null || url.getUserInfo()!=null) throw new IllegalArgumentException();
                }
            }
            for (String table : List.of("entries","positions","chapters","bookmarks")) {
                for (var item : data.path(table)) java.util.UUID.fromString(item.path("title_id").asString());
            }
            for (var item : data.path("entries")) {
                ReadingStatus.valueOf(item.path("status").asString());
                java.util.UUID.fromString(item.path("id").asString());
            }
            for (var item : data.path("sections")) java.util.UUID.fromString(item.path("id").asString());
            for (var item : data.path("memberships")) {
                java.util.UUID.fromString(item.path("section_id").asString());
                java.util.UUID.fromString(item.path("entry_id").asString());
            }
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
    }

    private void restoreWorks(JsonNode backup,List<java.util.UUID> inserted) {
        jdbc.sql(mapping()+"""
                INSERT INTO title_aliases(title_id,name,normalized,language,provenance)
                SELECT target,title,lower(title),'','backup' FROM mapping JOIN titles ON titles.id=mapping.target
                ON CONFLICT DO NOTHING
                """).param("titles",backup.path("titles").toString()).update();
        if(backup.path("version").asInt()!=2) return;
        restore(backup,"titleAliases","title_aliases","title_id,name,normalized,language,provenance",
                "mapping.target,b.name,b.normalized,b.language,b.provenance");
        restore(backup,"titleIdentifiers","title_external_identifiers","title_id,namespace,external_id,fetched_at",
                "mapping.target,b.namespace,b.external_id,b.fetched_at");
        var workIds=new java.util.HashSet<String>();
        for(var title:backup.path("titles")) workIds.add(title.path("work_id").asText());
        for(String original:workIds) {
            var members=jdbc.sql(mapping()+"""
                    SELECT mapping.target FROM mapping
                    JOIN jsonb_to_recordset(CAST(:titles AS jsonb)) AS src(id uuid,work_id uuid) ON src.id=mapping.origin
                    WHERE src.work_id=:work
                    """).param("titles",backup.path("titles").toString()).param("work",java.util.UUID.fromString(original))
                    .query(java.util.UUID.class).list();
            var fresh=members.stream().filter(inserted::contains).toList();
            if(fresh.isEmpty()) continue;
            var existing=members.stream().filter(id -> !inserted.contains(id)).findFirst();
            java.util.UUID target;
            if(existing.isPresent()) target=jdbc.sql("SELECT work_id FROM titles WHERE id=:id").param("id",existing.get()).query(java.util.UUID.class).single();
            else {
                target=java.util.UUID.fromString(original);
                boolean occupied=jdbc.sql("SELECT EXISTS(SELECT 1 FROM titles WHERE work_id=:id)").param("id",target).query(Boolean.class).single();
                if(occupied) target=java.util.UUID.randomUUID();
                jdbc.sql("INSERT INTO works(id) VALUES(:id) ON CONFLICT DO NOTHING").param("id",target).update();
            }
            jdbc.sql("UPDATE titles SET work_id=:work WHERE id IN (:ids)").param("work",target).param("ids",fresh).update();
        }
        var preferences=backup.path("workPreferences");
        if(preferences.isArray()) jdbc.sql(mapping()+"""
                INSERT INTO work_source_preferences(work_id,preferred_title_id,language)
                SELECT t.work_id,mapping.target,p.language FROM jsonb_to_recordset(CAST(:preferences AS jsonb))
                    AS p(preferred_title_id uuid,language text)
                JOIN mapping ON mapping.origin=p.preferred_title_id JOIN titles t ON t.id=mapping.target
                ORDER BY t.id ON CONFLICT(work_id) DO NOTHING
                """).param("titles",backup.path("titles").toString()).param("preferences",preferences.toString()).update();
        var decisions=backup.path("matchDecisions");
        if(decisions.isArray()) jdbc.sql(mapping()+"""
                INSERT INTO work_match_candidates(left_title,right_title,state,evidence,reason)
                SELECT least(a.target,b.target),greatest(a.target,b.target),d.state,d.evidence,d.reason
                FROM jsonb_to_recordset(CAST(:decisions AS jsonb)) AS d(left_title uuid,right_title uuid,state text,evidence jsonb,reason text)
                JOIN mapping a ON a.origin=d.left_title JOIN mapping b ON b.origin=d.right_title
                WHERE a.target<>b.target AND d.state IN('CONFIRM','REJECT') ON CONFLICT DO NOTHING
                """).param("titles",backup.path("titles").toString()).param("decisions",decisions.toString()).update();
        if(!inserted.isEmpty()) jdbc.sql("UPDATE work_catalog_version SET version=version+1 WHERE id=1").update();
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    org.springframework.http.ResponseEntity<org.springframework.http.ProblemDetail> invalidData() {
        var problem=org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,invalid().getMessage());
        problem.setProperty("code","INVALID_LIBRARY_BACKUP");
        return org.springframework.http.ResponseEntity.unprocessableContent().body(problem);
    }

    private static ApiException invalid() {
        return ApiException.problem(HttpStatus.UNPROCESSABLE_CONTENT,"INVALID_LIBRARY_BACKUP","Формат или данные резервной копии недопустимы.");
    }

    record Preview(int entries,int sections,int chapters,int bookmarks,int conflicts) {}
}
