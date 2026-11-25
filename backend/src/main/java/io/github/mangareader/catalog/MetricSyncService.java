package io.github.mangareader.catalog;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.library.TitleRepository;
import io.github.mangareader.source.SourceConnectionService;
import io.github.mangareader.source.mangadex.MangaDexSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="kadr.metrics-enabled",matchIfMissing=true)
public class MetricSyncService {
    private static final Logger LOG=LoggerFactory.getLogger(MetricSyncService.class);
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final MangaDexSource source;
    private final TitleRepository titles;
    private final SourceConnectionService connections;
    private final TransactionTemplate writes;

    MetricSyncService(JdbcClient jdbc,JsonMapper json,MangaDexSource source,TitleRepository titles,
                      SourceConnectionService connections,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.json=json;this.source=source;this.titles=titles;this.connections=connections;
        this.writes=new TransactionTemplate(transactions);
    }

    private boolean enabled() { return connections.sources().stream().anyMatch(s -> s.sourceId().equals("mangadex")); }

    @Scheduled(fixedDelay=21600000,initialDelay=10000)
    public void seed() {
        if(!enabled()) return;
        try {
            var popular=source.popularTitles();
            titles.syncCatalog(popular);
            for(var title : popular) jdbc.sql("""
                    INSERT INTO title_metric_state(title_id,priority)
                    SELECT id,10 FROM titles WHERE source_id='mangadex' AND external_title_id=:external
                    ON CONFLICT(title_id) DO UPDATE SET priority=10,
                        next_attempt_at=least(title_metric_state.next_attempt_at,now())
                    """).param("external",title.externalTitleId()).update();
        } catch(RuntimeException error) { LOG.warn("Metric discovery failed: {}",error.getMessage()); }
        jdbc.sql("""
                INSERT INTO title_metric_state(title_id)
                SELECT id FROM titles WHERE source_id='mangadex' ON CONFLICT DO NOTHING
                """).update();
    }

    @Scheduled(fixedDelay=2000,initialDelay=15000)
    public void tick() {
        if(!enabled()) return;
        var claim=jdbc.sql("""
                UPDATE title_metric_state SET lease_until=now()+interval '60 seconds',generation=generation+1
                WHERE title_id=(SELECT title_id FROM title_metric_state WHERE next_attempt_at<=now()
                    AND (lease_until IS NULL OR lease_until<now()) ORDER BY priority DESC,next_attempt_at,title_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING title_id,generation,attempts,(SELECT external_title_id FROM titles WHERE id=title_id) external_id
                """).query((row,n) -> new Claim(row.getObject("title_id",UUID.class),row.getLong("generation"),
                        row.getInt("attempts"),row.getString("external_id"))).optional();
        if(claim.isEmpty()) return;
        var task=claim.get();
        try {
            var payload=source.statistics(task.external());
            var stats=validated(payload,task.external());
            var metadata=source.getTitle(task.external());
            titles.syncCatalog(List.of(metadata));
            writes.executeWithoutResult(transaction -> {
                if(!owns(task)) return;
                long votes=0;
                for(var value:stats.path("rating").path("distribution")) votes+=value.asLong();
                jdbc.sql("""
                        INSERT INTO title_metric_observations(title_id,provider,provider_external_id,follows,rating,mean_rating,votes,distribution)
                        VALUES(:title,'mangadex',:external,:follows,:rating,:mean,:votes,CAST(:distribution AS jsonb))
                        ON CONFLICT(provider,provider_external_id,observation_day) DO UPDATE SET follows=excluded.follows,
                            rating=excluded.rating,mean_rating=excluded.mean_rating,votes=excluded.votes,
                            distribution=excluded.distribution,fetched_at=now()
                        """).param("title",task.title()).param("external",task.external()).param("follows",stats.path("follows").asLong())
                        .param("rating",stats.path("rating").path("bayesian").decimalValue())
                        .param("mean",stats.path("rating").path("average").isNumber()?stats.path("rating").path("average").decimalValue():null)
                        .param("votes",votes).param("distribution",stats.path("rating").path("distribution").toString()).update();
                jdbc.sql("""
                        UPDATE title_metric_state SET last_success_at=now(),lease_until=NULL,attempts=0,error=NULL,
                            next_attempt_at=now()+CASE WHEN priority>0 THEN interval '6 hours' ELSE interval '24 hours' END
                        WHERE title_id=:title AND generation=:generation
                        """).param("title",task.title()).param("generation",task.generation()).update();
            });
        } catch(RuntimeException error) {
            int delay=task.attempts()<2?30*(task.attempts()+1):21600;
            if(error instanceof ApiException api && api.extensions().get("retryAfterSeconds") instanceof Number wait)
                delay=Math.max(delay,Math.min(86400,wait.intValue()));
            String code=error instanceof ApiException api?api.code():"METRIC_BAD_RESPONSE";
            jdbc.sql("""
                    UPDATE title_metric_state SET lease_until=NULL,attempts=attempts+1,
                        next_attempt_at=now()+(:delay*interval '1 second'),error=CAST(:error AS jsonb)
                    WHERE title_id=:title AND generation=:generation
                    """).param("title",task.title()).param("generation",task.generation()).param("delay",delay)
                    .param("error",json.writeValueAsString(Map.of("code",code,"stage","METRICS","detail","Не удалось обновить показатели."))).update();
        }
    }

    static JsonNode validated(JsonNode payload,String id) {
        if(payload==null || !"ok".equals(payload.path("result").asText())) throw invalid();
        var stats=payload.path("statistics").path(id);
        var rating=stats.path("rating");
        if(!stats.path("follows").isIntegralNumber() || stats.path("follows").asLong()<0
                || !rating.path("bayesian").isNumber() || rating.path("bayesian").asDouble()<0
                || rating.path("bayesian").asDouble()>10 || !rating.path("distribution").isObject()) throw invalid();
        for(var entry:rating.path("distribution").properties()) {
            if(!entry.getKey().matches("[1-9]|10") || !entry.getValue().isIntegralNumber() || entry.getValue().asLong()<0) throw invalid();
        }
        return stats;
    }
    private static ApiException invalid() {return ApiException.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"METRIC_BAD_RESPONSE","Некорректные показатели источника.");}
    private boolean owns(Claim task) {return jdbc.sql("SELECT EXISTS(SELECT 1 FROM title_metric_state WHERE title_id=:id AND generation=:generation FOR UPDATE)")
            .param("id",task.title()).param("generation",task.generation()).query(Boolean.class).single();}
    private record Claim(UUID title,long generation,int attempts,String external) {}
}
