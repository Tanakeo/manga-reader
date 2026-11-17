package io.github.mangareader.source;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.error.ApiInputs;
import io.github.mangareader.source.SourceModels.Extension;
import io.github.mangareader.source.SourceModels.Source;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SourceConnectionService {
    private final MangaSourceRegistry registry;
    private final JdbcClient jdbc;

    public SourceConnectionService(MangaSourceRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    public List<Extension> extensions() {
        Set<String> connected = connectedIds();
        return registry.all().stream()
                .map(adapter -> new Extension(adapter.id(), adapter.displayName(), adapter.websiteUrl(),
                        connected.contains(adapter.id())))
                .toList();
    }

    public List<Source> sources() {
        return connectedAdapters().stream().map(this::metadata).toList();
    }

    public Source connect(String id) {
        MangaSource adapter = registry.require(ApiInputs.requireSourceId(id));
        jdbc.sql("""
                INSERT INTO connected_sources (source_id) VALUES (:id)
                ON CONFLICT (source_id) DO NOTHING
                """)
                .param("id", adapter.id())
                .update();
        return metadata(adapter);
    }

    public void disconnect(String id) {
        MangaSource adapter = registry.require(ApiInputs.requireSourceId(id));
        jdbc.sql("DELETE FROM connected_sources WHERE source_id = :id")
                .param("id", adapter.id())
                .update();
    }

    public List<MangaSource> connectedAdapters() {
        Set<String> connected = connectedIds();
        return registry.all().stream().filter(adapter -> connected.contains(adapter.id())).toList();
    }

    public MangaSource requireConnectedAdapter(String id) {
        MangaSource adapter = registry.require(ApiInputs.requireSourceId(id));
        boolean connected = jdbc.sql("SELECT EXISTS (SELECT 1 FROM connected_sources WHERE source_id = :id)")
                .param("id", adapter.id())
                .query(Boolean.class)
                .single();
        if (!connected) {
            throw ApiException.problem(HttpStatus.CONFLICT, "SOURCE_DISABLED", "Источник отключён.");
        }
        return adapter;
    }

    private Set<String> connectedIds() {
        return Set.copyOf(jdbc.sql("SELECT source_id FROM connected_sources").query(String.class).list());
    }

    private Source metadata(MangaSource adapter) {
        return new Source(adapter.id(), adapter.displayName(), adapter.websiteUrl(),
                adapter.supportsTags(), adapter.catalogSorts());
    }
}
