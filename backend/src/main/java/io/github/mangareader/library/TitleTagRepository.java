package io.github.mangareader.library;

import io.github.mangareader.source.SourceModels.SourceTitle;
import io.github.mangareader.source.SourceModels.Tag;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class TitleTagRepository {
    private static final TypeReference<Map<String, String>> NAMES = new TypeReference<>() {};
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    TitleTagRepository(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public void saveDictionary(String source, List<Tag> tags, Instant fetchedAt) {
        var rows = tags.stream().sorted(Comparator.comparing(Tag::id)).map(tag -> new MapSqlParameterSource()
                .addValue("source", source).addValue("id", tag.id()).addValue("group", tag.group())
                .addValue("names", json.writeValueAsString(tag.names())).addValue("version", tag.version())
                .addValue("fetched", fetchedAt.atOffset(ZoneOffset.UTC))).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO source_tags VALUES (:source, :id, :group, CAST(:names AS jsonb), :version, :fetched)
                ON CONFLICT (source_id, external_tag_id) DO UPDATE SET
                    tag_group = excluded.tag_group, names = excluded.names,
                    source_version = excluded.source_version, updated_at = excluded.updated_at
                WHERE coalesce(excluded.source_version, -1) >= coalesce(source_tags.source_version, -1)
                  AND (coalesce(excluded.source_version, -1) > coalesce(source_tags.source_version, -1)
                       OR excluded.updated_at > source_tags.updated_at)
                """, rows);
    }

    void replaceLinks(SourceTitle title) {
        if (title.tagsFetchedAt() == null) return;
        var values = new MapSqlParameterSource().addValue("source", title.sourceId())
                .addValue("external", title.externalTitleId()).addValue("version", title.tagsVersion())
                .addValue("fetched", title.tagsFetchedAt().atOffset(ZoneOffset.UTC));
        List<UUID> changed = jdbc.query("""
                UPDATE titles SET tags_synced_at = :fetched, tags_source_version = :version
                WHERE source_id = :source AND external_title_id = :external
                  AND (coalesce(CAST(:version AS integer), -1) >= coalesce(tags_source_version, -1))
                  AND (tags_synced_at IS NULL OR coalesce(CAST(:version AS integer), -1)
                       > coalesce(tags_source_version, -1) OR :fetched > tags_synced_at)
                RETURNING id
                """, values, (row, index) -> row.getObject(1, UUID.class));
        if (changed.isEmpty()) return;

        UUID titleId = changed.getFirst();
        jdbc.update("DELETE FROM title_tags WHERE title_id = :id", Map.of("id", titleId));
        var links = title.tags().stream().map(tag -> tag.id()).distinct()
                .map(tag -> new MapSqlParameterSource().addValue("title", titleId)
                        .addValue("source", title.sourceId()).addValue("tag", tag))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO title_tags VALUES (:title, :source, :tag)", links);
    }

    public List<Tag> forTitle(String source, String id) {
        return jdbc.query("""
                SELECT st.external_tag_id, st.tag_group, st.names
                FROM titles t JOIN title_tags tt ON tt.title_id = t.id
                JOIN source_tags st ON st.source_id = tt.source_id AND st.external_tag_id = tt.external_tag_id
                WHERE t.source_id = :source AND t.external_title_id = :id
                ORDER BY st.tag_group, coalesce(st.names->>'en', st.external_tag_id), st.external_tag_id
                """,
                Map.of("source", source, "id", id), (row, index) -> new Tag(row.getString(1),
                        row.getString(2), json.readValue(row.getString(3), NAMES)));
    }

    public List<Tag> dictionary(String source) {
        return jdbc.query("""
                SELECT external_tag_id,tag_group,names FROM source_tags WHERE source_id=:source
                ORDER BY tag_group,coalesce(names->>'en',external_tag_id),external_tag_id
                """, Map.of("source", source), (row, index) -> new Tag(row.getString(1),
                row.getString(2), json.readValue(row.getString(3), NAMES)));
    }

    public List<String> unsyncedMangaDexIds(String after) {
        return jdbc.queryForList("""
                SELECT external_title_id FROM titles
                WHERE source_id = 'mangadex' AND tags_synced_at IS NULL AND external_title_id > :after
                ORDER BY external_title_id LIMIT 50
                """, Map.of("after", after), String.class);
    }
}
