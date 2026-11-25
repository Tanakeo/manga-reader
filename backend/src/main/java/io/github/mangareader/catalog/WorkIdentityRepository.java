package io.github.mangareader.catalog;

import io.github.mangareader.source.SourceModels.SourceTitle;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class WorkIdentityRepository {
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public WorkIdentityRepository(JdbcClient jdbc, JsonMapper json) { this.jdbc=jdbc; this.json=json; }

    public static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    public void save(List<SourceTitle> titles) {
        for (SourceTitle title : titles) {
            UUID id=jdbc.sql("SELECT id FROM titles WHERE source_id=:source AND external_title_id=:external")
                    .param("source",title.sourceId()).param("external",title.externalTitleId()).query(UUID.class).single();
            alias(id,title.title(),"",title.sourceId());
            alias(id,title.titleRu(),"ru",title.sourceId());
            alias(id,title.titleEn(),"en",title.sourceId());
            var identity=title.identity();
            if (identity==null) continue;
            jdbc.sql("DELETE FROM title_external_identifiers WHERE title_id=:id").param("id",id).update();
            jdbc.sql("UPDATE titles SET identity_metadata=CAST(:data AS jsonb) WHERE id=:id")
                    .param("id",id).param("data",json.writeValueAsString(identity)).update();
            for (var name : identity.aliases()) alias(id,name.name(),name.language(),title.sourceId());
            for (var entry : identity.identifiers().entrySet()) {
                jdbc.sql("""
                        INSERT INTO title_external_identifiers(title_id,namespace,external_id)
                        VALUES(:title,:namespace,:external) ON CONFLICT(title_id,namespace,external_id)
                        DO UPDATE SET fetched_at=now()
                        """).param("title",id).param("namespace",entry.getKey()).param("external",entry.getValue()).update();
            }
        }
    }

    private void alias(UUID title,String name,String language,String source) {
        if (name==null || name.isBlank()) return;
        jdbc.sql("""
                INSERT INTO title_aliases(title_id,name,normalized,language,provenance)
                VALUES(:title,:name,:normalized,:language,:source) ON CONFLICT DO NOTHING
                """).param("title",title).param("name",name).param("normalized",normalize(name))
                .param("language",language==null ? "" : language).param("source",source).update();
    }
}
