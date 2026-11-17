package io.github.mangareader.source.mangadex;

import io.github.mangareader.error.ApiInputs;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MangaDexImageController {
    private final MangaDexSource source;

    MangaDexImageController(MangaDexSource source) {
        this.source = source;
    }

    @GetMapping("/api/v1/sources/mangadex/titles/{titleId}/chapters/{chapterId}/images/{index}")
    ResponseEntity<byte[]> image(@PathVariable String titleId, @PathVariable String chapterId,
                                 @PathVariable int index, @RequestParam(required = false) String language) {
        ApiInputs.requireMatch("language", language, ApiInputs.LANGUAGE, 32);
        MangaDexClient.Image image = source.getImage(titleId, chapterId, language, index);
        return response(image, CacheControl.noStore());
    }

    @GetMapping("/api/v1/sources/mangadex/titles/{titleId}/covers/{fileName}")
    ResponseEntity<byte[]> cover(@PathVariable String titleId, @PathVariable String fileName) {
        return response(source.getCover(titleId, fileName), CacheControl.maxAge(Duration.ofHours(1)));
    }

    private ResponseEntity<byte[]> response(MangaDexClient.Image image, CacheControl cache) {
        return ResponseEntity.ok().cacheControl(cache).contentType(image.type())
                .header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }
}
