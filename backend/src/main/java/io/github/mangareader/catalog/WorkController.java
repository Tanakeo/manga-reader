package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1")
class WorkController {
    private final WorkService works;
    private final WorkMatcher matcher;
    private final boolean localAdmin;
    WorkController(WorkService works,WorkMatcher matcher,@Value("${kadr.local-admin-enabled:false}") boolean localAdmin) {
        this.works=works;this.matcher=matcher;this.localAdmin=localAdmin;
    }
    @GetMapping("/works/{id}")
    JsonNode detail(@PathVariable UUID id,@RequestParam(name="chapterLanguage",required=false) List<String> languages) {
        if(languages!=null && languages.stream().anyMatch(l -> !List.of("ru","en").contains(l)))
            throw CatalogSyncService.invalid("chapterLanguage","Доступны ru и en.");
        return works.detail(id,languages==null?List.of():languages);
    }
    @PutMapping("/works/{id}/source-preference")
    JsonNode preference(@PathVariable UUID id,@RequestBody Preference body) {
        if(body.titleId()==null || body.expectedVersion()==null || body.expectedVersion()<0
                || body.language()!=null && !List.of("ru","en").contains(body.language()))
            throw CatalogSyncService.invalid("titleId","Проверьте источник, язык и версию.");
        return works.prefer(id,body.titleId(),body.language(),body.expectedVersion());
    }
    @GetMapping("/admin/work-matches")
    JsonNode matches(HttpServletRequest request,@RequestParam(defaultValue="PENDING") String state,
                     @RequestParam(defaultValue="30") int limit,@RequestParam(defaultValue="0") int cursor) {
        authorize(request);
        return matcher.candidates(state,limit,cursor);
    }
    @PostMapping("/admin/work-matches/{id}/decision")
    JsonNode decide(HttpServletRequest request,@PathVariable UUID id,@RequestBody Decision body) {
        authorize(request);
        if(body.expectedVersion()==null) throw CatalogSyncService.invalid("expectedVersion","Укажите версию.");
        return matcher.decide(id,body.decision(),body.expectedVersion(),body.reason());
    }
    @PostMapping("/admin/works/{id}/split")
    JsonNode split(HttpServletRequest request,@PathVariable UUID id,@RequestBody Split body) {
        authorize(request);
        if(body.expectedVersion()==null) throw CatalogSyncService.invalid("expectedVersion","Укажите версию.");
        return matcher.split(id,body.titleIds(),body.expectedVersion(),body.reason());
    }
    private void authorize(HttpServletRequest request) {
        if(!localAdmin || !List.of("127.0.0.1","0:0:0:0:0:0:0:1","::1").contains(request.getRemoteAddr()))
            throw ApiException.problem(HttpStatus.FORBIDDEN,"ADMIN_DISABLED","Служебные операции доступны в локальном режиме.");
    }
    record Preference(UUID titleId,String language,Long expectedVersion) {}
    record Decision(String decision,Long expectedVersion,String reason) {}
    record Split(List<UUID> titleIds,Long expectedVersion,String reason) {}
}
