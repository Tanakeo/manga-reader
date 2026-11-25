package io.github.mangareader.library;

import io.github.mangareader.source.SourceModels.License;
import io.github.mangareader.source.SourceModels.SourceTitle;
import io.github.mangareader.source.SourceModels.Tag;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TitleRepository {
    private static final String TITLE_VALUES = """
            excluded.source_name, excluded.title, excluded.source_url, excluded.author, excluded.description,
            excluded.cover_url, excluded.license_code, excluded.license_url, excluded.title_ru, excluded.title_en,
            excluded.publication_status, excluded.release_year
            """;
    private static final String CATALOG_VALUES = """
            excluded.source_name, excluded.title, excluded.source_url,
            COALESCE(excluded.author, titles.author), COALESCE(excluded.description, titles.description),
            COALESCE(excluded.cover_url, titles.cover_url),
            COALESCE(excluded.license_code, titles.license_code), COALESCE(excluded.license_url, titles.license_url),
            COALESCE(excluded.title_ru, titles.title_ru), COALESCE(excluded.title_en, titles.title_en),
            COALESCE(excluded.publication_status, titles.publication_status), COALESCE(excluded.release_year, titles.release_year)
            """;
    private static final String UPSERT_TEMPLATE = """
            INSERT INTO titles (id, source_id, external_title_id, source_name, title, source_url,
                                author, description, cover_url, license_code, license_url,
                                title_ru, title_en, publication_status, release_year)
            VALUES (:id, :sourceId, :externalTitleId, :sourceName, :title, :sourceUrl,
                    :author, :description, :coverUrl, :licenseCode, :licenseUrl,
                    :titleRu, :titleEn, :publicationStatus, :releaseYear)
            ON CONFLICT (source_id, external_title_id) DO UPDATE SET
                (source_name, title, source_url, author, description, cover_url, license_code,
                 license_url, title_ru, title_en, publication_status, release_year) = (%1$s)
            WHERE (titles.source_name, titles.title, titles.source_url, titles.author, titles.description,
                   titles.cover_url, titles.license_code, titles.license_url, titles.title_ru,
                   titles.title_en, titles.publication_status, titles.release_year) IS DISTINCT FROM
                  (%1$s)
            """;
    private static final String UPSERT = UPSERT_TEMPLATE.formatted(TITLE_VALUES);
    private static final String UPSERT_FROM_CATALOG = UPSERT_TEMPLATE.formatted(CATALOG_VALUES);
    private final NamedParameterJdbcTemplate jdbc;
    private final TitleTagRepository tags;
    private final io.github.mangareader.catalog.WorkIdentityRepository identity;

    TitleRepository(NamedParameterJdbcTemplate jdbc, TitleTagRepository tags,
                    io.github.mangareader.catalog.WorkIdentityRepository identity) {
        this.jdbc = jdbc;
        this.tags = tags;
        this.identity = identity;
    }

    @Transactional
    public List<SourceTitle> sync(List<SourceTitle> titles) {
        return sync(titles, UPSERT);
    }

    @Transactional
    public List<SourceTitle> syncCatalog(List<SourceTitle> titles) {
        return sync(titles, UPSERT_FROM_CATALOG);
    }

    private List<SourceTitle> sync(List<SourceTitle> titles, String upsert) {
        if (titles.isEmpty()) {
            return List.of();
        }
        var snapshots = titles.stream().filter(title -> title.tagsFetchedAt() != null)
                .collect(Collectors.groupingBy(SourceTitle::sourceId, TreeMap::new, Collectors.toList()));
        snapshots.forEach((source, entries) -> {
            var dictionary = new ArrayList<Tag>();
            entries.forEach(title -> dictionary.addAll(title.tags()));
            var fetchedAt = entries.stream().map(SourceTitle::tagsFetchedAt).min(Comparator.naturalOrder()).orElseThrow();
            tags.saveDictionary(source, dictionary, fetchedAt);
        });
        var parameters = titles.stream()
                .sorted(Comparator.comparing(SourceTitle::sourceId).thenComparing(SourceTitle::externalTitleId))
                .map(TitleRepository::parameters).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(upsert, parameters);
        identity.save(titles);
        titles.stream().sorted(Comparator.comparing(SourceTitle::sourceId).thenComparing(SourceTitle::externalTitleId))
                .forEach(tags::replaceLinks);

        var bySource = titles.stream().collect(Collectors.groupingBy(SourceTitle::sourceId));
        var saved = new HashMap<String, Map<String, SourceTitle>>();
        bySource.forEach((source, items) -> {
            var ids = items.stream().map(SourceTitle::externalTitleId).distinct().toList();
            var rows = jdbc.query("""
                    SELECT source_id, external_title_id, source_name, title, source_url, author,
                           description, cover_url, license_code, license_url, title_ru, title_en,
                           publication_status, release_year
                    FROM titles WHERE source_id = :sourceId AND external_title_id IN (:ids)
                    """, Map.of("sourceId", source, "ids", ids), TitleRepository::readTitle);
            saved.put(source, rows.stream().collect(Collectors.toMap(SourceTitle::externalTitleId, Function.identity())));
        });
        return titles.stream().map(title -> saved.get(title.sourceId()).get(title.externalTitleId())).toList();
    }

    UUID lockTitleId(String sourceId, String externalTitleId) {
        return jdbc.queryForObject("""
                SELECT id FROM titles WHERE source_id = :sourceId AND external_title_id = :externalTitleId
                FOR UPDATE
                """, Map.of("sourceId", sourceId, "externalTitleId", externalTitleId), UUID.class);
    }

    public java.util.Optional<SourceTitle> find(String sourceId, String externalTitleId) {
        return jdbc.query("""
                SELECT * FROM titles WHERE source_id=:source AND external_title_id=:external
                """, Map.of("source", sourceId, "external", externalTitleId), TitleRepository::readTitle)
                .stream().findFirst();
    }

    private static MapSqlParameterSource parameters(SourceTitle title) {
        return new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("sourceId", title.sourceId())
                .addValue("externalTitleId", title.externalTitleId())
                .addValue("sourceName", title.sourceName())
                .addValue("title", title.title())
                .addValue("sourceUrl", title.sourceUrl())
                .addValue("author", title.author())
                .addValue("description", title.description())
                .addValue("coverUrl", title.coverUrl())
                .addValue("licenseCode", title.license() == null ? null : title.license().code())
                .addValue("licenseUrl", title.license() == null ? null : title.license().url())
                .addValue("titleRu", title.titleRu())
                .addValue("titleEn", title.titleEn())
                .addValue("publicationStatus", title.publicationStatus())
                .addValue("releaseYear", title.releaseYear());
    }

    private static SourceTitle readTitle(ResultSet row, int index) throws SQLException {
        String license = row.getString("license_code");
        return new SourceTitle(row.getString("source_id"), row.getString("external_title_id"),
                row.getString("source_name"), row.getString("title"), row.getString("author"), row.getString("cover_url"),
                row.getString("description"), row.getString("source_url"),
                license == null ? null : new License(license, row.getString("license_url")),
                row.getString("title_ru"), row.getString("title_en"), row.getString("publication_status"),
                row.getObject("release_year", Integer.class));
    }
}
