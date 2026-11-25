package io.github.mangareader.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.mangareader.library.TitleRepository;
import io.github.mangareader.source.SourceModels.SourceTitle;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"kadr.metrics-enabled=false"})
@Testcontainers
@Transactional
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class WorkCatalogTest {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:17.12-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username",POSTGRES::getUsername);
        properties.add("spring.datasource.password",POSTGRES::getPassword);
    }
    @Autowired TitleRepository titles;
    @Autowired JdbcClient jdbc;
    @Autowired WorkService works;
    @Autowired WorkMatcher matcher;
    @Autowired JsonMapper json;

    private UUID seed(String source,String external,String name) {
        titles.syncCatalog(List.of(new SourceTitle(source,external,source,name,"Author",null,null,
                "https://example.org/"+external,null,null,null,null,2020)));
        return jdbc.sql("SELECT id FROM titles WHERE source_id=:s AND external_title_id=:e")
                .param("s",source).param("e",external).query(UUID.class).single();
    }

    @Test void mergeAndSplitPreserveLibraryAndPaginateGroups() {
        jdbc.sql("INSERT INTO connected_sources VALUES('mangakakalot') ON CONFLICT DO NOTHING").update();
        UUID a=seed("peppercarrot","work-test-a","Shared work");
        UUID b=seed("mangakakalot","work-test-b","Shared work");
        seed("peppercarrot","work-test-c","Shared work extra");
        jdbc.sql("INSERT INTO library_entries(id,title_id,status,note,created_at,updated_at) VALUES(:id,:title,'READING','Keep note',now(),now())")
                .param("id",UUID.randomUUID()).param("title",b).update();
        matcher.discover();
        UUID match=jdbc.sql("SELECT id FROM work_match_candidates WHERE left_title=least(:a,:b) AND right_title=greatest(:a,:b)")
                .param("a",a).param("b",b).query(UUID.class).single();
        matcher.decide(match,"CONFIRM",0,"Verified test identity");
        var first=works.list(null,"Shared work",List.of(),"",List.of(),1,null,false);
        assertThat(first.path("page").path("total").asInt()).isEqualTo(2);
        var second=works.list(null,"Shared work",List.of(),"",List.of(),1,first.path("page").path("nextCursor").asText(),false);
        assertThat(second.path("page").path("hasMore").asBoolean()).isFalse();
        assertThat(first.path("items").path(0).path("workId")).isNotEqualTo(second.path("items").path(0).path("workId"));
        UUID work=jdbc.sql("SELECT work_id FROM titles WHERE id=:id").param("id",a).query(UUID.class).single();
        assertThat(works.summary(work,List.of(),"").path("sources")).hasSize(2);
        assertThat(jdbc.sql("SELECT note FROM library_entries WHERE title_id=:id").param("id",b).query(String.class).single()).isEqualTo("Keep note");
        matcher.split(work,List.of(b),1,"Correct mistaken match");
        assertThat(jdbc.sql("SELECT count(DISTINCT work_id) FROM titles WHERE id IN(:a,:b)")
                .param("a",a).param("b",b).query(Integer.class).single()).isEqualTo(2);
        assertThatThrownBy(() -> works.list(null,"Shared work",List.of(),"",List.of(),1,first.path("page").path("nextCursor").asText(),false))
                .hasMessageContaining("Каталог обновился");
        titles.syncCatalog(List.of(new SourceTitle("mangakakalot","work-test-b","MangaKakalot","Shared work","Author",null,null,"https://example.org/b",null,null,null,null,2020)));
        assertThat(jdbc.sql("SELECT note FROM library_entries WHERE title_id=:id").param("id",b).query(String.class).single()).isEqualTo("Keep note");
    }

    @Test void ambiguousNamesStaySeparateAndMetricErrorsNeverBecomeZero() {
        var a=json.readTree("{\"title\":\"Same\",\"author\":\"Author A\"}");
        var b=json.readTree("{\"title\":\"Same\",\"author\":\"Author B\"}");
        assertThat(WorkMatcher.safeMatch(a,b,json.createObjectNode())).isFalse();
        assertThatThrownBy(() -> MetricSyncService.validated(json.readTree("{\"result\":\"ok\",\"statistics\":{}}"),"missing"))
                .hasMessageContaining("Некорректные показатели");
        assertThatThrownBy(() -> MetricSyncService.validated(null,"missing")).isInstanceOf(RuntimeException.class);
    }

    @Test void repeatSyncKeepsWorkAndPreferenceRequiresCurrentRevision() {
        UUID title=seed("peppercarrot","stable","Stable identity");
        UUID work=jdbc.sql("SELECT work_id FROM titles WHERE id=:id").param("id",title).query(UUID.class).single();
        works.prefer(work,title,"en",0);
        assertThatThrownBy(() -> works.prefer(work,title,"en",0)).hasMessageContaining("Предпочтение изменилось");
        assertThat(seed("peppercarrot","stable","Stable identity updated")).isEqualTo(title);
        assertThat(jdbc.sql("SELECT work_id FROM titles WHERE id=:id").param("id",title).query(UUID.class).single()).isEqualTo(work);
    }
}
