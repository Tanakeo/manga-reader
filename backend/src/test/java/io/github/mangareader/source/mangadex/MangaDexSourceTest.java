package io.github.mangareader.source.mangadex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.mangareader.library.TitleTagRepository;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.json.JsonMapper;

class MangaDexSourceTest {
    @Test
    void incrementalCatalogUsesUpdatedDateAndPreservesWindowKind() {
        var client = mock(MangaDexClient.class);
        var uris = new DefaultUriBuilderFactory("https://api.mangadex.org");
        var json = JsonMapper.builder().build();
        when(client.get(any())).thenAnswer(invocation -> {
            Function<UriBuilder, URI> request = invocation.getArgument(0);
            assertThat(request.apply(uris.builder()).getQuery())
                    .contains("updatedAtSince=2026-11-19T23:59:59", "order[updatedAt]=asc")
                    .doesNotContain("createdAtSince");
            return json.readTree("{\"data\":[],\"total\":0,\"offset\":0}");
        });
        var adapter = new MangaDexSource(client, mock(TitleTagRepository.class), "");
        var result = adapter.catalogPage(adapter.incrementalCursor(java.time.Instant.parse("2026-11-20T00:00:00Z")));
        assertThat(result.stopReason()).isEqualTo("EXHAUSTED");
    }

    @Test
    void missingFeedTotalCannotConfirmZeroChapters() {
        var client = mock(MangaDexClient.class);
        var json = JsonMapper.builder().build();
        when(client.get(any())).thenReturn(json.readTree("{\"data\":[]}"));
        var adapter = new MangaDexSource(client, mock(TitleTagRepository.class), "");
        assertThatExceptionOfType(io.github.mangareader.error.ApiException.class)
                .isThrownBy(() -> adapter.scanChapters("32d76d19-8a05-4db0-9fc2-e0b0648fe9d0"))
                .matches(error -> "SOURCE_BAD_RESPONSE".equals(error.code()));
    }

    @Test
    void catalogAdvancesItsTimeWindowAndReportsAnUnsplitBoundary() {
        var client = mock(MangaDexClient.class);
        var json = JsonMapper.builder().build();
        when(client.get(any())).thenReturn(json.readTree("""
                {"total":50000,"offset":9900,"data":[
                  {"id":"32d76d19-8a05-4db0-9fc2-e0b0648fe9d0","relationships":[],
                   "attributes":{"contentRating":"safe","title":{"en":"Sample"},"tags":[],
                   "createdAt":"2020-01-01T00:00:00Z"}}]}
                """));
        var adapter = new MangaDexSource(client, mock(TitleTagRepository.class), "");
        var next = adapter.catalogPage("|9900");
        assertThat(next.nextCursor()).isEqualTo("2019-12-31T23:59:59|0");
        assertThat(next.stopReason()).isNull();
        var boundary = adapter.catalogPage("2020-01-01T00:00:00|9900");
        assertThat(boundary.nextCursor()).isNull();
        assertThat(boundary.stopReason()).isEqualTo("SOURCE_LIMIT");
    }

    @Test
    void sendsEveryFilterAndOrderBeforeTheLimitAndKeysTheCacheByQuery() {
        var client = mock(MangaDexClient.class);
        var tagRepository = mock(TitleTagRepository.class);
        var uris = new DefaultUriBuilderFactory("https://api.mangadex.org");
        var json = JsonMapper.builder().build();
        var queryStrings = new ArrayList<String>();
        String a = "0bc90acb-ccc1-44ca-a34a-b9f3a73259d0";
        String b = "3e2b8dae-350e-4ab8-a8ce-016e844b9f0d";
        when(client.get(any())).thenAnswer(invocation -> {
            Function<UriBuilder, URI> request = invocation.getArgument(0);
            URI uri = request.apply(uris.builder());
            if (uri.getPath().equals("/manga/tag")) {
                return json.readTree("""
                        {"data":[
                          {"id":"%s","attributes":{"name":{"en":"Reincarnation"},"group":"theme"}},
                          {"id":"%s","attributes":{"name":{"en":"Long Strip"},"group":"format"}}
                        ]}
                        """.formatted(a, b));
            }
            queryStrings.add(uri.getQuery());
            return json.readTree("{\"data\":[]}");
        });
        var adapter = new MangaDexSource(client, tagRepository, "");
        for (String sort : List.of("relevance", "year_asc", "year_desc", "latest_chapter_desc")) {
            adapter.searchTitles("Prison", List.of(a, b), sort);
        }
        adapter.searchTitles("Prison", List.of(a, b), "year_desc");
        adapter.searchTitles("Prison", List.of(b), "year_desc");
        assertThat(queryStrings).hasSize(5);
        assertThat(queryStrings.subList(0, 4)).allSatisfy(query ->
                assertThat(query).contains("includedTags[]=" + a, "includedTags[]=" + b, "includedTagsMode=AND",
                        "title=Prison", "limit=24", "availableTranslatedLanguage[]=ru", "contentRating[]=safe"));
        assertThat(queryStrings.get(0)).contains("order[relevance]=desc");
        assertThat(queryStrings.get(1)).contains("order[year]=asc");
        assertThat(queryStrings.get(2)).contains("order[year]=desc");
        assertThat(queryStrings.get(3)).contains("order[latestUploadedChapter]=desc");
        assertThat(queryStrings.get(4)).contains("includedTags[]=" + b).doesNotContain("includedTags[]=" + a);
    }
}
