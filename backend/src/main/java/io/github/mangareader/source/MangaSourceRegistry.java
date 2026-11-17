package io.github.mangareader.source;

import io.github.mangareader.error.ApiException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MangaSourceRegistry {
    private final Map<String, MangaSource> sources;

    public MangaSourceRegistry(List<MangaSource> sources) {
        this.sources = sources.stream()
                .collect(Collectors.toUnmodifiableMap(MangaSource::id, Function.identity()));
    }

    public MangaSource require(String id) {
        MangaSource source = sources.get(id);
        if (source == null) {
            throw ApiException.problem(HttpStatus.NOT_FOUND, "SOURCE_NOT_FOUND", "Источник не найден.");
        }
        return source;
    }

    public List<MangaSource> all() {
        return sources.values().stream().sorted(Comparator.comparing(MangaSource::id)).toList();
    }
}
