package io.github.mangareader.library;

import io.github.mangareader.library.LibrarySections.Section;
import io.github.mangareader.source.SourceModels.Items;
import java.net.URI;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/library/sections")
class LibrarySectionsController {
    private final LibrarySections sections;

    LibrarySectionsController(LibrarySections sections) {
        this.sections = sections;
    }

    @GetMapping
    Items<Section> list() {
        return new Items<>(sections.list());
    }

    @GetMapping("/{sectionId}")
    Section get(@PathVariable String sectionId) {
        return sections.get(LibraryRequests.sectionId(sectionId));
    }

    @PostMapping
    ResponseEntity<Section> create(@RequestBody JsonNode body) {
        var section = sections.create(LibraryRequests.sectionName(body));
        return ResponseEntity.created(URI.create("/api/v1/library/sections/" + section.id())).body(section);
    }

    @PatchMapping("/{sectionId}")
    Section rename(@PathVariable String sectionId, @RequestBody JsonNode body) {
        return sections.rename(LibraryRequests.sectionId(sectionId), LibraryRequests.sectionName(body));
    }

    @DeleteMapping("/{sectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String sectionId) {
        sections.delete(LibraryRequests.sectionId(sectionId));
    }

    @PostMapping("/{sectionId}/entries")
    Section addEntries(@PathVariable String sectionId, @RequestBody JsonNode body) {
        return sections.addEntries(LibraryRequests.sectionId(sectionId), LibraryRequests.sectionEntries(body));
    }

    @DeleteMapping("/{sectionId}/entries/{entryId}")
    Section removeEntry(@PathVariable String sectionId, @PathVariable String entryId) {
        return sections.removeEntry(LibraryRequests.sectionId(sectionId), LibraryRequests.entryId(entryId));
    }
}
