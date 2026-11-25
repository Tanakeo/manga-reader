package io.github.mangareader.library;

import static io.github.mangareader.library.ReadingModels.*;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ReadingController {
    private static final String TITLE = "/sources/{sourceId}/titles/{externalTitleId}";
    private final ReadingService reading;

    ReadingController(ReadingService reading) { this.reading = reading; }

    @GetMapping(TITLE + "/reading")
    State state(@PathVariable String sourceId, @PathVariable String externalTitleId) {
        return reading.state(reading.title(sourceId,externalTitleId));
    }

    @PostMapping(TITLE + "/reading/sessions")
    Session session(@PathVariable String sourceId, @PathVariable String externalTitleId, @RequestBody Claim body) {
        return reading.claim(reading.title(sourceId,externalTitleId),body);
    }

    @PutMapping(TITLE + "/reading/progress")
    Saved save(@PathVariable String sourceId, @PathVariable String externalTitleId, @RequestBody Save body) {
        return reading.save(reading.title(sourceId,externalTitleId),body);
    }

    @PatchMapping(TITLE + "/chapters/{chapterId}/reading")
    Saved mark(@PathVariable String sourceId, @PathVariable String externalTitleId, @PathVariable String chapterId,
               @RequestParam String language, @RequestBody Mark body) {
        return reading.mark(reading.title(sourceId,externalTitleId),chapterId,language,body);
    }

    @GetMapping("/reading-history")
    History history(@RequestParam(defaultValue="30") int limit, @RequestParam(required=false) String cursor) {
        return reading.history(limit,cursor);
    }
}
