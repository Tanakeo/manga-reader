package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WorkMatcher {
    private final JdbcClient jdbc;
    private final JsonMapper json;
    WorkMatcher(JdbcClient jdbc,JsonMapper json) {this.jdbc=jdbc;this.json=json;}

    @Scheduled(fixedDelay=300000,initialDelay=20000)
    @Transactional
    public void discover() {
        jdbc.sql("""
                INSERT INTO work_match_candidates(left_title,right_title,evidence)
                SELECT DISTINCT a.title_id,b.title_id,jsonb_build_object('rule','alias-candidate-v1','normalized',a.normalized)
                FROM title_aliases a JOIN title_aliases b ON a.normalized=b.normalized AND a.title_id<b.title_id
                JOIN titles ta ON ta.id=a.title_id JOIN titles tb ON tb.id=b.title_id
                WHERE ta.source_id<>tb.source_id AND ta.work_id<>tb.work_id AND length(a.normalized)>2
                AND NOT EXISTS(SELECT 1 FROM work_match_candidates c WHERE c.left_title=a.title_id AND c.right_title=b.title_id)
                LIMIT 500 ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO work_match_candidates(left_title,right_title,evidence)
                SELECT DISTINCT a.title_id,b.title_id,jsonb_build_object('rule','external-id-v1','namespace',a.namespace,'id',a.external_id)
                FROM title_external_identifiers a JOIN title_external_identifiers b USING(namespace,external_id)
                JOIN titles ta ON ta.id=a.title_id JOIN titles tb ON tb.id=b.title_id
                WHERE a.title_id<b.title_id AND ta.source_id<>tb.source_id AND ta.work_id<>tb.work_id
                LIMIT 500 ON CONFLICT DO NOTHING
                """).update();
        for (var candidate : candidates("PENDING",50,0).path("items")) {
            if (safeMatch(candidate.path("left"),candidate.path("right"),candidate.path("evidence")))
                decide(UUID.fromString(candidate.path("id").asText()),"CONFIRM",candidate.path("version").asLong(),"auto-strict-v1");
            else jdbc.sql("UPDATE work_match_candidates SET updated_at=now() WHERE id=:id")
                    .param("id",UUID.fromString(candidate.path("id").asText())).update();
        }
    }

    static boolean safeMatch(JsonNode left,JsonNode right,JsonNode evidence) {
        var a=left.path("identity_metadata"); var b=right.path("identity_metadata");
        if (!"MANGA".equals(a.path("kind").asText()) || !"MANGA".equals(b.path("kind").asText())
                || !"ORIGINAL".equals(a.path("edition").asText()) || !"ORIGINAL".equals(b.path("edition").asText())) return false;
        if (a.path("originalLanguage").asText("").isBlank()
                || !a.path("originalLanguage").asText("").equals(b.path("originalLanguage").asText(""))) return false;
        boolean shared=false;
        for(var id:a.path("identifiers").properties()) {
            var other=b.path("identifiers").path(id.getKey());
            if(other.isMissingNode()) continue;
            if(!id.getValue().equals(other)) return false;
            shared=true;
        }
        String author=left.path("author").asText("");
        String otherAuthor=right.path("author").asText("");
        if(!author.isBlank() && !otherAuthor.isBlank()
                && !WorkIdentityRepository.normalize(author).equals(WorkIdentityRepository.normalize(otherAuthor))) return false;
        if(left.path("release_year").isNumber() && right.path("release_year").isNumber()
                && !left.path("release_year").equals(right.path("release_year"))) return false;
        if(shared) return true;
        return !author.isBlank() && WorkIdentityRepository.normalize(author).equals(WorkIdentityRepository.normalize(right.path("author").asText("")))
                && left.path("release_year").isInt() && left.path("release_year").equals(right.path("release_year"))
                && WorkIdentityRepository.normalize(left.path("title").asText()).equals(WorkIdentityRepository.normalize(right.path("title").asText()));
    }

    public JsonNode candidates(String state,int limit,int offset) {
        if (!List.of("PENDING","CONFIRM","REJECT").contains(state) || limit<1 || limit>100 || offset<0)
            throw CatalogSyncService.invalid("state","Некорректные параметры списка.");
        var response=json.createObjectNode();
        response.set("items",json.valueToTree(jdbc.sql("""
                SELECT (to_jsonb(c)||jsonb_build_object('left',to_jsonb(a)-'search_document',
                        'right',to_jsonb(b)-'search_document'))::text
                FROM work_match_candidates c JOIN titles a ON a.id=c.left_title JOIN titles b ON b.id=c.right_title
                WHERE c.state=:state ORDER BY c.updated_at,c.id LIMIT :limit OFFSET :offset
                """).param("state",state).param("limit",limit).param("offset",offset).query(String.class).list().stream().map(json::readTree).toList()));
        return response;
    }

    @Transactional
    public JsonNode decide(UUID id,String decision,long version,String reason) {
        if (!List.of("CONFIRM","REJECT").contains(decision) || reason==null || reason.isBlank() || reason.length()>2000)
            throw CatalogSyncService.invalid("decision","Укажите решение и причину.");
        lock();
        var c=jdbc.sql("SELECT to_jsonb(c)::text FROM work_match_candidates c WHERE id=:id FOR UPDATE")
                .param("id",id).query(String.class).optional().map(json::readTree).orElseThrow(() ->
                        ApiException.problem(HttpStatus.NOT_FOUND,"WORK_NOT_FOUND","Совпадение не найдено."));
        if(c.path("version").asLong()!=version) throw conflict();
        if("CONFIRM".equals(decision)) {
            UUID left=UUID.fromString(c.path("left_title").asText()),right=UUID.fromString(c.path("right_title").asText());
            UUID into=work(left),from=work(right);
            if(!into.equals(from)) merge(from,into,reason);
        }
        jdbc.sql("UPDATE work_match_candidates SET state=:decision,reason=:reason,version=version+1,updated_at=now() WHERE id=:id")
                .param("decision",decision).param("reason",reason).param("id",id).update();
        return json.valueToTree(Map.of("id",id,"decision",decision,"version",version+1));
    }

    private void merge(UUID from,UUID into,String reason) {
        event(from,into,reason);
        jdbc.sql("UPDATE titles SET work_id=:into WHERE work_id=:from").param("into",into).param("from",from).update();
        jdbc.sql("""
                INSERT INTO work_source_preferences(work_id,preferred_title_id,language,version)
                SELECT :into,preferred_title_id,language,version FROM work_source_preferences WHERE work_id=:from
                ON CONFLICT DO NOTHING
                """).param("into",into).param("from",from).update();
        jdbc.sql("DELETE FROM work_source_preferences WHERE work_id=:from").param("from",from).update();
        jdbc.sql("UPDATE work_redirects SET target_id=:into WHERE target_id=:from").param("into",into).param("from",from).update();
        jdbc.sql("INSERT INTO work_redirects(old_id,target_id) VALUES(:from,:into) ON CONFLICT(old_id) DO UPDATE SET target_id=excluded.target_id")
                .param("from",from).param("into",into).update();
        changed(into);
    }

    @Transactional
    public JsonNode split(UUID work,List<UUID> titles,long version,String reason) {
        lock();
        long current=jdbc.sql("SELECT version FROM works WHERE id=:id").param("id",work).query(Long.class).optional().orElseThrow(WorkMatcher::conflict);
        if(current!=version) throw conflict();
        if(titles==null || titles.isEmpty() || titles.size()>100 || reason==null || reason.isBlank() || reason.length()>2000)
            throw CatalogSyncService.invalid("titleIds","Укажите варианты и причину исправления.");
        var members=jdbc.sql("SELECT id FROM titles WHERE work_id=:work ORDER BY id").param("work",work).query(UUID.class).list();
        if(!members.containsAll(titles) || titles.size()>=members.size() || titles.stream().distinct().count()!=titles.size()) throw conflict();
        UUID next=UUID.randomUUID();
        jdbc.sql("INSERT INTO works(id) VALUES(:id)").param("id",next).update();
        event(work,next,reason);
        jdbc.sql("UPDATE titles SET work_id=:next WHERE work_id=:work AND id IN (:titles)")
                .param("next",next).param("work",work).param("titles",titles).update();
        jdbc.sql("UPDATE work_redirects SET target_id=:next WHERE old_id IN (:titles) AND old_id<>:work")
                .param("next",next).param("titles",titles).param("work",work).update();
        jdbc.sql("UPDATE work_source_preferences SET work_id=:next WHERE work_id=:work AND preferred_title_id IN (:titles)")
                .param("next",next).param("work",work).param("titles",titles).update();
        jdbc.sql("""
                UPDATE work_match_candidates c SET state='REJECT',reason=:reason,version=version+1
                FROM titles a,titles b WHERE a.id=c.left_title AND b.id=c.right_title
                AND ((a.work_id=:next AND b.work_id=:work) OR (b.work_id=:next AND a.work_id=:work))
                """).param("next",next).param("work",work).param("reason",reason).update();
        changed(work);
        return json.valueToTree(Map.of("workId",work,"splitWorkId",next,"titleIds",titles));
    }

    private void event(UUID from,UUID into,String reason) {
        jdbc.sql("""
                INSERT INTO work_merge_events(from_work,into_work,membership,reason)
                SELECT :from,:into,jsonb_build_object('titles',(SELECT jsonb_agg(jsonb_build_object('titleId',id,'workId',work_id))
                    FROM titles WHERE work_id IN (:from,:into)),
                    'preferences',(SELECT jsonb_agg(to_jsonb(p)) FROM work_source_preferences p WHERE work_id IN (:from,:into))),:reason
                """).param("from",from).param("into",into).param("reason",reason).update();
    }
    private UUID work(UUID title) {return jdbc.sql("SELECT work_id FROM titles WHERE id=:id").param("id",title).query(UUID.class).single();}
    private void lock() {jdbc.sql("SELECT version FROM work_catalog_version WHERE id=1 FOR UPDATE").query(Long.class).single();}
    private void changed(UUID work) {
        jdbc.sql("UPDATE works SET version=version+1 WHERE id=:id").param("id",work).update();
        jdbc.sql("UPDATE work_catalog_version SET version=version+1 WHERE id=1").update();
    }
    private static ApiException conflict() {return ApiException.problem(HttpStatus.CONFLICT,"WORK_VERSION_CONFLICT","Состав произведения изменился. Обновите данные.");}
}
