package io.github.mangareader.source;

import io.github.mangareader.source.SourceModels.Extension;
import io.github.mangareader.source.SourceModels.Items;
import io.github.mangareader.source.SourceModels.Source;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SourceController {
    private final SourceConnectionService connections;

    public SourceController(SourceConnectionService connections) {
        this.connections = connections;
    }

    @GetMapping("/extensions")
    public Items<Extension> extensions() {
        return new Items<>(connections.extensions());
    }

    @GetMapping("/sources")
    public Items<Source> sources() {
        return new Items<>(connections.sources());
    }

    @PutMapping("/sources/{sourceId}")
    public Source connect(@PathVariable String sourceId) {
        return connections.connect(sourceId);
    }

    @DeleteMapping("/sources/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@PathVariable String sourceId) {
        connections.disconnect(sourceId);
    }
}
