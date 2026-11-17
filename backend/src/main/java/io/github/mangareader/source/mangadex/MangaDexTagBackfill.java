package io.github.mangareader.source.mangadex;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.library.TitleRepository;
import io.github.mangareader.library.TitleTagRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "kadr.tags.backfill", havingValue = "true")
class MangaDexTagBackfill implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(MangaDexTagBackfill.class);
    private final MangaDexSource source;
    private final TitleRepository titles;
    private final TitleTagRepository tags;

    MangaDexTagBackfill(MangaDexSource source, TitleRepository titles, TitleTagRepository tags) {
        this.source = source;
        this.titles = titles;
        this.tags = tags;
    }

    @Override
    public void run(ApplicationArguments args) {
        String after = "";
        try {
            while (true) {
                var ids = tags.unsyncedMangaDexIds(after);
                if (ids.isEmpty()) return;
                var snapshots = source.fetchByIds(ids);
                titles.sync(snapshots);
                LOG.info("MangaDex backfill: {} requested, {} usable snapshots", ids.size(),
                        snapshots.stream().filter(title -> title.tagsFetchedAt() != null).count());
                after = ids.getLast();
            }
        } catch (ApiException error) {
            LOG.warn("MangaDex backfill paused at {} ({})", after, error.code());
        }
    }
}
