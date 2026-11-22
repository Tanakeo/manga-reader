package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiInputs;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/sources/{sourceId}")
class CatalogSyncController {
    private final CatalogSyncService sync;
    private final LocalCatalogService catalog;

    CatalogSyncController(CatalogSyncService sync, LocalCatalogService catalog) {
        this.sync = sync;
        this.catalog = catalog;
    }

    @PostMapping("/catalog-sync")
    ResponseEntity<JsonNode> start(@PathVariable String sourceId, @RequestBody Start request) {
        ApiInputs.requireSourceId(sourceId);
        return ResponseEntity.accepted().body(sync.start(sourceId, request.mode()));
    }

    @GetMapping("/catalog-sync")
    JsonNode state(@PathVariable String sourceId) {
        var result = (tools.jackson.databind.node.ObjectNode) sync.status(sourceId);
        result.set("coverage", catalog.coverage(sourceId, List.of()));
        return result;
    }

    @PostMapping("/catalog-sync/{runId}/pause")
    ResponseEntity<JsonNode> pause(@PathVariable String sourceId, @PathVariable UUID runId) {
        return ResponseEntity.accepted().body(sync.pause(sourceId, runId, true));
    }

    @PostMapping("/catalog-sync/{runId}/resume")
    ResponseEntity<JsonNode> resume(@PathVariable String sourceId, @PathVariable UUID runId) {
        return ResponseEntity.accepted().body(sync.pause(sourceId, runId, false));
    }

    @PostMapping("/titles/{externalTitleId}/chapter-checks")
    ResponseEntity<JsonNode> check(@PathVariable String sourceId, @PathVariable String externalTitleId) {
        ApiInputs.requireMatch("externalTitleId", externalTitleId, ApiInputs.EXTERNAL_ID, 200);
        return ResponseEntity.accepted().body(sync.enqueue(sourceId, externalTitleId));
    }

    record Start(String mode) {}
}
