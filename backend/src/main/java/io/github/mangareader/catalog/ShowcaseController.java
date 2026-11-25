package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/showcase")
class ShowcaseController {
    private final WorkService works;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final com.github.benmanes.caffeine.cache.Cache<String,JsonNode> homeCache=
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder().maximumSize(20)
                    .expireAfterWrite(java.time.Duration.ofSeconds(90)).build();
    ShowcaseController(WorkService works,JdbcClient jdbc,JsonMapper json) {this.works=works;this.jdbc=jdbc;this.json=json;}

    @GetMapping
    JsonNode home(@RequestParam(defaultValue="8") int limitPerCollection,
                  @RequestParam(name="chapterLanguage",required=false) List<String> languages) {
        if(limitPerCollection<1 || limitPerCollection>12) throw CatalogSyncService.invalid("limitPerCollection","От 1 до 12.");
        String version=jdbc.sql("SELECT version::text FROM work_catalog_version WHERE id=1").query(String.class).single();
        String sources=jdbc.sql("SELECT coalesce(string_agg(source_id,',' ORDER BY source_id),'') FROM connected_sources").query(String.class).single();
        String key=limitPerCollection+":"+languages+":"+version+":"+sources;
        return homeCache.get(key,unused -> buildHome(limitPerCollection,languages));
    }

    private JsonNode buildHome(int limitPerCollection,List<String> languages) {
        var result=json.createObjectNode().put("generatedAt",java.time.Instant.now().toString());
        var collections=result.putArray("collections");
        for(String id:List.of("popular","rating","latest")) collections.add(collection(id,languages,limitPerCollection,null,null));
        var genres=jdbc.sql("""
                SELECT external_tag_id FROM source_tags WHERE source_id='mangadex' AND tag_group='genre'
                ORDER BY CASE names->>'en' WHEN 'Fantasy' THEN 0 WHEN 'Adventure' THEN 1 ELSE 2 END,external_tag_id LIMIT 2
                """).query(String.class).list();
        for(String tag:genres) collections.add(collection("genre",languages,limitPerCollection,null,tag));
        result.set("providerStates",providerStates());
        var first=collections.get(0);
        result.set("sourceStates",first.path("sourceStates"));
        result.put("partial",first.path("partial").asBoolean());
        return result;
    }

    @GetMapping("/{id}")
    JsonNode collection(@PathVariable String id,@RequestParam(name="chapterLanguage",required=false) List<String> languages,
                        @RequestParam(defaultValue="24") int limit,@RequestParam(required=false) String cursor,
                        @RequestParam(required=false) String tagId) {
        String sort=switch(id) {
            case "popular","rating" -> id;
            case "latest" -> "latest_chapter_desc";
            case "genre" -> "genre";
            default -> throw ApiException.problem(HttpStatus.NOT_FOUND,"COLLECTION_NOT_FOUND","Подборка не найдена.");
        };
        String title=switch(id) {
            case "popular" -> "Популярное на MangaDex";
            case "rating" -> "Высоко оценённое";
            case "latest" -> "Недавно обновлённое";
            default -> "По жанрам";
        };
        List<String> tags=List.of();
        if(id.equals("genre")) {
            if(tagId==null) throw CatalogSyncService.invalid("tagId","Выберите жанр.");
            var name=jdbc.sql("SELECT coalesce(names->>'ru',names->>'en') FROM source_tags WHERE source_id='mangadex' AND external_tag_id=:id AND tag_group='genre'")
                    .param("id",tagId).query(String.class).optional().orElseThrow(() -> ApiException.invalidTags(List.of(tagId)));
            title=switch(name) {case "Fantasy" -> "Фэнтези";case "Adventure" -> "Приключения";default -> name;};
            tags=List.of(tagId);
        }
        var response=(tools.jackson.databind.node.ObjectNode)works.list(null,"",tags,sort,
                languages==null?List.of():languages,limit,cursor,true);
        response.put("id",id).put("title",title);
        if(tagId!=null) response.put("tagId",tagId);
        response.put("basis",switch(id) {
            case "popular" -> "По числу подписчиков MangaDex";
            case "rating" -> "Байесовская оценка MangaDex · от 100 голосов";
            case "latest" -> "По дате публикации доступных глав";
            default -> "Жанры MangaDex · доступные для чтения произведения";
        });
        response.set("providerStates",providerStates());
        response.put("state",response.path("items").isEmpty()?"EMPTY":"READY");
        boolean metrics=List.of("popular","rating").contains(id);
        var provider=response.path("providerStates").path(0);
        if(metrics && provider.path("lastSuccessAt").isNull()) response.put("state","UNAVAILABLE");
        else if(metrics && provider.path("stale").asBoolean()) response.put("state","STALE");
        return response;
    }

    private JsonNode providerStates() {
        return json.readTree(jdbc.sql("""
                SELECT jsonb_build_array(jsonb_build_object('provider','mangadex',
                    'lastSuccessAt',max(last_success_at),'stale',coalesce(max(last_success_at)<now()-interval '24 hours',true),
                    'availableMetrics',count(*) FILTER(WHERE last_success_at IS NOT NULL),
                    'queuedTitles',count(*),'scope','COLLECTED_TITLES',
                    'error',(SELECT error FROM title_metric_state WHERE error IS NOT NULL ORDER BY next_attempt_at DESC LIMIT 1)))::text
                FROM title_metric_state
                """).query(String.class).single());
    }
}
