package io.github.mangareader.download;

import io.github.mangareader.download.DownloadModels.Download;
import io.github.mangareader.download.DownloadModels.Downloads;
import io.github.mangareader.download.DownloadModels.Manifest;
import io.github.mangareader.download.DownloadModels.Request;
import io.github.mangareader.source.SourceModels.ChapterPages;
import java.net.URI;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/downloads")
class DownloadController {
    private final DownloadService downloads;

    DownloadController(DownloadService downloads) {
        this.downloads = downloads;
    }

    @GetMapping
    Downloads list(@RequestParam(required = false) String sourceId,
                   @RequestParam(required = false) String externalTitleId) {
        return downloads.list(sourceId, externalTitleId);
    }

    @PostMapping
    ResponseEntity<Download> start(@RequestBody Request request) {
        return result(downloads.create(request));
    }

    @GetMapping("/{id}")
    Download get(@PathVariable UUID id) {
        return downloads.get(id);
    }

    @PostMapping("/{id}/retry")
    ResponseEntity<Download> retry(@PathVariable UUID id) {
        return result(downloads.retry(id));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Download> delete(@PathVariable UUID id) {
        return downloads.delete(id).map(item -> ResponseEntity.accepted().body(item))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/queue/{action}")
    ResponseEntity<Download> control(@PathVariable UUID id,@PathVariable String action) {
        return result(downloads.control(id, action));
    }

    @GetMapping("/{id}/pages")
    ChapterPages pages(@PathVariable UUID id) {
        return downloads.pages(id);
    }

    @GetMapping("/{id}/manifest")
    Manifest manifest(@PathVariable UUID id) {
        return downloads.manifest(id);
    }

    @GetMapping({"/{id}/pages/{index}/image", "/{id}/images/{index}"})
    ResponseEntity<Resource> image(@PathVariable UUID id, @PathVariable int index) {
        var image = downloads.image(id, index);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(image.type())
                .header("X-Content-Type-Options", "nosniff").body(image.resource());
    }

    private ResponseEntity<Download> result(Download item) {
        HttpStatus status = switch (item.status()) {
            case "READY", "FAILED" -> HttpStatus.OK;
            default -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).location(URI.create("/api/v1/downloads/" + item.id())).body(item);
    }
}
