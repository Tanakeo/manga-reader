package io.github.mangareader.library;

import io.github.mangareader.library.LibraryModels.LibraryEntryView;
import io.github.mangareader.source.SourceModels.Items;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/library")
public class LibraryController {
    private final LibraryService library;

    public LibraryController(LibraryService library) {
        this.library = library;
    }

    @GetMapping
    public Items<LibraryEntryView> list() {
        return new Items<>(library.list());
    }

    @GetMapping("/{entryId}")
    public LibraryEntryView get(@PathVariable String entryId) {
        return library.get(LibraryRequests.entryId(entryId));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<LibraryEntryView> create(@RequestBody(required = false) JsonNode body) {
        LibraryEntryView entry = library.create(LibraryRequests.create(body));
        return ResponseEntity.created(URI.create("/api/v1/library/" + entry.id())).body(entry);
    }

    @PatchMapping(value = "/{entryId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public LibraryEntryView update(@PathVariable String entryId, @RequestBody(required = false) JsonNode body) {
        return library.update(LibraryRequests.entryId(entryId), LibraryRequests.update(body));
    }

    @DeleteMapping("/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String entryId) {
        library.delete(LibraryRequests.entryId(entryId));
    }
}
