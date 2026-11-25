package io.github.mangareader.source.mangakakalot;

import io.github.mangareader.error.ApiInputs;
import io.github.mangareader.library.TitleRepository;
import io.github.mangareader.source.SourceConnectionService;
import io.github.mangareader.source.SourceModels.SourceTitle;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MangaKakalotCoverController {
    private final MangaKakalotSource source;
    private final SourceConnectionService connections;
    private final TitleRepository titles;

    MangaKakalotCoverController(MangaKakalotSource source, SourceConnectionService connections,
                               TitleRepository titles) {
        this.source = source;
        this.connections = connections;
        this.titles = titles;
    }

    @GetMapping("/api/v1/sources/mangakakalot/titles/{titleId}/cover")
    ResponseEntity<byte[]> cover(@PathVariable String titleId) {
        ApiInputs.requireMatch("titleId", titleId, ApiInputs.EXTERNAL_ID, 200);
        connections.requireConnectedAdapter(source.id());
        String savedCover = titles.find(source.id(), titleId).map(SourceTitle::coverUrl).orElse(null);
        var image = source.loadCover(titleId, savedCover);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .contentType(image.type()).header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }
}
