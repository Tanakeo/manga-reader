package io.github.mangareader.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.source.SourceModels.Page;
import io.github.mangareader.source.SourceModels.SourceChapter;
import io.github.mangareader.source.SourceModels.SourceTitle;
import io.github.mangareader.source.mangakakalot.MangaKakalotSource;
import io.github.mangareader.source.mangalib.MangaLibSource;
import java.net.URI;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class PublicSourcesTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final PublicSourceClient client = spy(new PublicSourceClient(json));

    @Test
    void truncatedKakalotFeedCannotConfirmAnEmptyTitle() {
        var source = new MangaKakalotSource(client);
        doReturn(json.readTree("""
                {"success":true,"data":{"chapters":[],"pagination":{"total":9,"offset":0,"limit":-1}}}
                """)).when(client).json(eq(URI.create("https://www.katamanga.com/api/manga/sample/chapters?limit=-1")),
                isNull(), notNull());
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> source.scanChapters("sample"))
                .matches(error -> "SOURCE_BAD_RESPONSE".equals(error.code()));
    }

    @Test
    void mangaKakalotUsesPublicFeedOnDeniedSearchAndKeepsImageOrder() {
        var source = new MangaKakalotSource(client);
        URI feed = URI.create("https://www.katamanga.com/manga-list/hot-manga?page=1");
        doReturn(Jsoup.parse("""
                <div class="list-comic-item-wrap">
                  <h3><a href="/manga/sample">Sample</a></h3>
                  <img src="https://img-r1.2xstorage.com/cover.webp">
                </div>
                """, feed.toString())).when(client).html(eq(feed), isNull());
        doThrow(denied()).when(client).html(
                argThat(uri -> uri != null && uri.getPath().startsWith("/search/story/")), isNull());
        doReturn(json.readTree("""
                {"success":true,"data":{"chapters":[
                  {"chapter_slug":"chapter-2","chapter_name":"Second","chapter_num":2},
                  {"chapter_slug":"chapter-1","chapter_name":"First","chapter_num":1}],
                  "pagination":{"total":2,"limit":-1,"offset":0}}}
                """)).when(client).json(
                eq(URI.create("https://www.katamanga.com/api/manga/sample/chapters?limit=-1")), isNull(), notNull());
        doReturn(Jsoup.parse("""
                <div class="manga-info-top">
                  <h1>Sample</h1><ul><li>Author(s) : Author</li></ul>
                </div>
                <div id="contentBox"><h2>Summary</h2><p>Example description.</p></div>
                """)).when(client).html(eq(URI.create("https://www.katamanga.com/manga/sample")), notNull());
        doReturn(Jsoup.parse("""
                <script>
                  var cdns = ["https://imgs-2.2xstorage.com"];
                  var chapterImages = ["sample/1/b.webp", "sample/1/a.webp"];
                </script>
                """)).when(client).html(eq(URI.create("https://www.katamanga.com/manga/sample/chapter-1")), notNull());

        assertThat(source.searchTitles("sample")).extracting(SourceTitle::externalTitleId).containsExactly("sample");
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> source.searchTitles("unknown"))
                .matches(exception -> "SOURCE_ACCESS_DENIED".equals(exception.code()));
        assertThat(source.listChapters("sample")).extracting(SourceChapter::id).containsExactly("chapter-1", "chapter-2");
        var chapter = source.getPages("sample", "chapter-1", "en");
        assertThat(chapter.attribution().author()).isEqualTo("Author");
        assertThat(chapter.nextChapterId()).isEqualTo("chapter-2");
        assertThat(chapter.pages()).extracting(Page::imageUrl).containsExactly(
                "https://imgs-2.2xstorage.com/sample/1/b.webp", "https://imgs-2.2xstorage.com/sample/1/a.webp");
    }

    @Test
    void mangaLibReadsPublicMetadataAndSkipsRestrictedChapterBranches() {
        var source = new MangaLibSource(client);
        doReturn(Jsoup.parse("""
                <script type="application/ld+json">
                  {"@type":"CollectionPage","mainEntity":{"itemListElement":[
                    {"url":"http://mangalib.me/ru/manga/42--sample","name":"Пример"}]}}
                </script>
                """)).when(client).html(eq(URI.create("https://mangalib.me/ru/catalog")), isNull());
        doReturn(Jsoup.parse("""
                <script type="application/ld+json">
                  {"@type":"CreativeWorkSeries","name":"Пример","alternateName":["Sample"],"author":[{"name":"Автор"}]}
                </script>
                """)).when(client).html(eq(URI.create("https://mangalib.me/ru/manga/42--sample")), notNull());
        doReturn(json.readTree("""
                {"data":[
                  {"id":1,"volume":"1","number":"1","branches":[{"branch_id":1,"restricted_view":{"is_open":false}}]},
                  {"id":2,"volume":"1","number":"2","branches":[
                    {"branch_id":1,"restricted_view":{"is_open":false}},
                    {"branch_id":5,"teams":[{"name":"Команда"}],"restricted_view":null}]}]}
                """)).when(client).json(eq(URI.create("https://api2.mangalib.me/api/manga/42--sample/chapters")),
                eq("1"), notNull());
        doReturn(json.readTree("""
                {"data":{"pages":[
                  {"slug":2,"url":"https://img.mixlib.me/sample/b.webp"},
                  {"slug":1,"url":"//manga/sample/a.webp"}]}}
                """)).when(client).json(eq(URI.create(
                "https://api2.mangalib.me/api/manga/42--sample/chapter?volume=1&number=2&branch_id=5")), eq("1"), notNull());
        doReturn(json.readTree("""
                {"data":{"imageServers":[
                  {"id":"main","url":"https://img.mixlib.me","site_ids":[1]},
                  {"id":"secondary","url":"https://img3.cdnrulib.link","site_ids":[1]}]}}
                """)).when(client).json(eq(URI.create("https://api2.mangalib.me/api/constants?fields%5B%5D=imageServers")),
                eq("1"), isNull());

        assertThat(source.searchTitles("")).extracting(SourceTitle::title).containsExactly("Пример");
        assertThat(source.listChapters("42--sample")).extracting(SourceChapter::id).containsExactly("2~5");
        var chapter = source.getPages("42--sample", "2", "ru");
        assertThat(chapter.attribution().creditsText()).contains("Команда");
        assertThat(chapter.pages()).extracting(Page::imageUrl).containsExactly(
                "https://img3.cdnrulib.link/manga/sample/a.webp", "https://img.mixlib.me/sample/b.webp");
    }

    private static ApiException denied() {
        return ApiException.problem(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_ACCESS_DENIED", "Доступ ограничен.");
    }
}
