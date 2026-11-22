package io.github.mangareader.source.mangalib;

import static io.github.mangareader.source.PublicSourceClient.badResponse;
import static io.github.mangareader.source.PublicSourceClient.httpsUrl;
import static io.github.mangareader.source.PublicSourceClient.text;
import static io.github.mangareader.source.PublicSourceClient.url;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.mangareader.error.ApiException;
import io.github.mangareader.error.ApiInputs;
import io.github.mangareader.source.MangaSource;
import io.github.mangareader.source.PublicSourceClient;
import io.github.mangareader.source.SourceScan.CatalogPage;
import io.github.mangareader.source.SourceScan.ChapterResult;
import io.github.mangareader.source.SourceModels.Attribution;
import io.github.mangareader.source.SourceModels.ChapterPages;
import io.github.mangareader.source.SourceModels.Page;
import io.github.mangareader.source.SourceModels.SourceChapter;
import io.github.mangareader.source.SourceModels.SourceTitle;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.jsoup.Jsoup;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class MangaLibSource implements MangaSource {
    private static final String ORIGIN = "https://mangalib.me";
    private static final String API = "https://api2.mangalib.me";
    private static final String SITE_ID = "1";
    private static final String TITLE_PREFIX = "/ru/manga/";
    private static final String[] IMAGE_DOMAINS = {"mangalib.me", "mixlib.me", "cdnlibs.org", "lib.social", "mangalib.org", "cdnrulib.link", "imglib.info"};
    private static final List<String> LANGUAGES = List.of("ru");

    private final PublicSourceClient client;
    private final Cache<String, List<SourceTitle>> catalog =
            Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, List<SourceTitle>> publicFeed =
            Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, SourceTitle> titles =
            Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(10)).build();
    private final Cache<String, List<Chapter>> chapters =
            Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, URI> imageServer =
            Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofMinutes(30)).build();
    private final Cache<String, List<URI>> imageServers =
            Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofMinutes(10)).build();

    public MangaLibSource(PublicSourceClient client) {
        this.client = client;
    }

    @Override public String id() { return "mangalib"; }
    @Override public String displayName() { return "MangaLib"; }
    @Override public String websiteUrl() { return ORIGIN; }
    @Override public List<String> supportedLanguages() { return LANGUAGES; }
    @Override public List<String> imageDomains() { return List.of(IMAGE_DOMAINS); }

    @Override
    public void refreshManifest(String chapterId) {
        imageServer.invalidateAll();
        imageServers.invalidateAll();
    }

    @Override
    public List<URI> alternativeImages(URI original) {
        return servers().stream().map(server -> server.resolve(original.getRawPath()))
                .filter(candidate -> !candidate.equals(original)).distinct().limit(2).toList();
    }

    @Override
    public CatalogPage catalogPage(String cursor) {
        String[] parts = cursor == null ? new String[]{"1", ""} : cursor.split("\\|", -1);
        int page = Integer.parseInt(parts[0]);
        var uri = url(API, "api", "manga").queryParam("site_id[]", 1).queryParam("page", page);
        if (!parts[1].isBlank()) uri.queryParam("seed", parts[1]);
        var response = client.json(uri.build().encode().toUri(), SITE_ID, null);
        var meta = response.path("meta");
        if (!meta.path("has_next_page").isBoolean() || meta.path("current_page").asInt(-1) != page) {
            throw badResponse("Missing MangaLib catalog pagination");
        }
        var items = array(response.path("data")).stream().map(this::searchResult).toList();
        boolean next = meta.path("has_next_page").asBoolean();
        if (next && items.isEmpty()) throw badResponse("Empty nonterminal MangaLib page");
        String seed = parts[1].isBlank() ? meta.path("seed").asText("") : parts[1];
        return new CatalogPage(items, next ? (page + 1) + "|" + seed : null, null,
                next ? null : "EXHAUSTED", "page=" + page + ", next=" + next + ", seed=" + seed);
    }

    @Override
    public ChapterResult scanChapters(String id) {
        chapters.invalidate(id);
        var items = listChapters(id);
        return new ChapterResult(items, true, items.size(), 0, "EXHAUSTED");
    }

    @Override
    public List<SourceTitle> searchTitles(String query) {
        return catalog.get(query.strip().toLowerCase(Locale.ROOT), this::search);
    }

    private List<SourceTitle> search(String query) {
        if (query.isEmpty()) return publicCatalog();
        try {
            URI uri = url(API, "api", "manga").queryParam("q", query).queryParam("site_id[]", SITE_ID)
                    .build().encode().toUri();
            return array(data(uri, null)).stream().map(this::searchResult).toList();
        } catch (ApiException exception) {
            if (!"SOURCE_ACCESS_DENIED".equals(exception.code())) throw exception;
            var matches = publicCatalog().stream().filter(title ->
                    (title.title().toLowerCase(Locale.ROOT) + " " + title.externalTitleId().replace('-', ' '))
                            .contains(query)).toList();
            if (matches.isEmpty()) throw exception;
            return matches;
        }
    }

    private List<SourceTitle> publicCatalog() {
        return publicFeed.get("", key -> {
            var html = client.html(url(ORIGIN, "ru", "catalog").build().toUri(), null);
            var list = client.structuredData(html, "CollectionPage").path("mainEntity").path("itemListElement");
            var covers = new HashMap<String, String>();
            for (var image : html.select("a[href*=/ru/manga/] > img[src]")) {
                covers.put(image.parent().attr("href"), image.absUrl("src"));
            }
            return array(list).stream().map(item -> {
                String address = text(item.path("url"));
                String id = titleId(address);
                String name = text(item.path("name"));
                if (name == null) throw badResponse("Missing catalog title name");
                return title(id, name, null, null, covers.get(address), null, null);
            }).toList();
        });
    }

    private SourceTitle searchResult(JsonNode item) {
        String slug = text(item.path("slug_url"));
        String name = text(item.path("rus_name"));
        String english = text(item.path("eng_name"));
        if (name == null) name = text(item.path("name"));
        if (slug == null || name == null || !ApiInputs.EXTERNAL_ID.matcher(slug).matches()) {
            throw badResponse("Invalid MangaLib search result");
        }
        return title(slug, name, english, null, text(item.path("cover").path("default")), null, null);
    }

    @Override
    public SourceTitle getTitle(String id) {
        return titles.get(id, key -> {
            var uri = url(ORIGIN, "ru", "manga", key).build().encode().toUri();
            var html = client.html(uri, MangaLibSource::titleMissing);
            var data = client.structuredData(html, "CreativeWorkSeries");
            String name = text(data.path("name"));
            if (name == null) throw badResponse("Missing title name");
            String date = text(data.path("datePublished"));
            Integer year = date != null && date.matches("[0-9]{4}.*") ? Integer.valueOf(date.substring(0, 4)) : null;
            return title(key, name, text(data.path("alternateName").path(0)), text(data.path("author").path(0).path("name")),
                    text(data.path("image")), text(data.path("description")), year);
        });
    }

    @Override
    public List<SourceChapter> listChapters(String id) {
        return chapters.get(id, this::loadChapters).stream().map(Chapter::summary).toList();
    }

    private List<Chapter> loadChapters(String titleId) {
        var uri = url(API, "api", "manga", titleId, "chapters").build().encode().toUri();
        var result = new ArrayList<Chapter>();
        for (JsonNode item : array(data(uri, MangaLibSource::titleMissing))) {
            String id = text(item.path("id"));
            String volume = text(item.path("volume"));
            String number = text(item.path("number"));
            if (id == null || !id.matches("\\d+") || volume == null || number == null) {
                throw badResponse("Invalid MangaLib chapter identity");
            }
            var branches = array(item.path("branches")).stream()
                    .filter(candidate -> candidate.path("restricted_view").isNull()
                            || candidate.path("restricted_view").isMissingNode()
                            || candidate.path("restricted_view").path("is_open").asBoolean(false))
                    .toList();
            for (var branch : branches) {
            String branchId = text(branch.path("branch_id"));
            if (branchId != null && !branchId.matches("\\d+")) throw badResponse("Invalid translation branch");
            String label = "Том " + volume + ", глава " + number;
            String name = text(item.path("name"));
            String published = text(branch.path("created_at"));
            var summary = new SourceChapter(branchId == null ? id : id + "~" + branchId,
                    number.matches("\\d+(?:\\.\\d+)?") ? new BigDecimal(number) : null,
                    name == null ? label : label + " · " + name, LANGUAGES, null, id,
                    text(branch.path("teams").path(0).path("name")),
                    PublicSourceClient.instant(published),volume,number);
            result.add(new Chapter(summary, volume, number, branchId, text(branch.path("teams").path(0).path("name"))));
            }
        }
        return result.stream().sorted(Comparator.comparing((Chapter chapter) -> numericVolume(chapter.volume()),
                        Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(Chapter::volume)
                .thenComparing(chapter -> chapter.summary().number(), Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    private static BigDecimal numericVolume(String value) {
        try { return new BigDecimal(value); }
        catch (NumberFormatException exception) { return null; }
    }

    @Override
    public ChapterPages getPages(String id, String chapterId, String language) {
        if (!"ru".equalsIgnoreCase(language)) throw ApiException.unavailableLanguage(LANGUAGES);
        var available = chapters.get(id, this::loadChapters);
        for (int index = 0; index < available.size(); index++) {
            Chapter chapter = available.get(index);
            if (chapterId.equals(chapter.summary().id()) || chapterId.equals(chapter.summary().logicalChapterId())) {
                var result = readChapter(id, available, index, chapter);
                return new ChapterPages(chapterId, result.title(), result.language(), result.pageCount(),
                        result.previousChapterId(), result.nextChapterId(), result.attribution(), result.pages());
            }
        }
        throw chapterMissing();
    }

    private ChapterPages readChapter(String id, List<Chapter> available, int index, Chapter chapter) {
        var uri = url(API, "api", "manga", id, "chapter")
                .queryParam("volume", chapter.volume()).queryParam("number", chapter.number());
        var website = url(ORIGIN, "ru", id, "read", "v" + chapter.volume(), "c" + chapter.number());
        if (chapter.branch() != null) {
            uri.queryParam("branch_id", chapter.branch());
            website.queryParam("bid", chapter.branch());
        }
        var images = array(data(uri.build().encode().toUri(), MangaLibSource::chapterMissing).path("pages"))
                .stream().sorted(Comparator.comparingInt(page -> page.path("slug").asInt())).toList();
        if (images.isEmpty()) throw badResponse("Chapter contains no pages");
        var pages = new ArrayList<Page>();
        for (JsonNode image : images) {
            String path = text(image.path("url"));
            URI address = httpsUrl(path, IMAGE_DOMAINS);
            if (address == null && path != null && (path.startsWith("/manga/") || path.startsWith("//manga/"))) {
                // MangaLib also prefixes its CDN paths with two slashes.
                String relativePath = path.startsWith("//") ? path.substring(1) : path;
                URI cdn = imageServer.get(SITE_ID, this::loadImageServer);
                address = httpsUrl(cdn.resolve(relativePath).toString(), IMAGE_DOMAINS);
            }
            if (address == null) throw badResponse("Invalid MangaLib page URL");
            pages.add(new Page(pages.size(), address.toString()));
        }
        var attribution = new Attribution(getTitle(id).author(),
                chapter.team() == null ? "Источник перевода — MangaLib." : "Перевод — " + chapter.team() + ". Источник — MangaLib.",
                website.build().encode().toUri().toString(), null);
        return new ChapterPages(chapter.summary().id(), chapter.summary().title(), "ru", pages.size(),
                index > 0 ? available.get(index - 1).summary().id() : null,
                index + 1 < available.size() ? available.get(index + 1).summary().id() : null,
                attribution, List.copyOf(pages));
    }

    private URI loadImageServer(String siteId) {
        return servers().stream().findFirst().orElseThrow(() -> badResponse("No image server for MangaLib"));
    }

    private List<URI> servers() {
        return imageServers.get(SITE_ID, ignored -> {
            URI uri = url(API, "api", "constants").queryParam("fields[]", "imageServers").build().encode().toUri();
            var servers = array(data(uri, null).path("imageServers"));
            return servers.stream().filter(server -> List.of("secondary", "compress", "main", "download")
                            .contains(server.path("id").asText()))
                    .filter(server -> array(server.path("site_ids")).stream().anyMatch(site -> SITE_ID.equals(site.asText())))
                    .sorted(Comparator.comparing(server -> !"secondary".equals(server.path("id").asText())))
                    .map(server -> httpsUrl(text(server.path("url")), IMAGE_DOMAINS))
                    .filter(java.util.Objects::nonNull).distinct().toList();
        });
    }

    private JsonNode data(URI uri, Supplier<ApiException> missing) {
        return client.json(uri, SITE_ID, missing).path("data");
    }

    private List<JsonNode> array(JsonNode value) {
        if (!value.isArray()) throw badResponse("Expected a MangaLib list");
        var items = new ArrayList<JsonNode>();
        value.forEach(items::add);
        return items;
    }

    private String titleId(String value) {
        if (value == null) throw badResponse("Missing MangaLib URL");
        URI uri = httpsUrl(value.replaceFirst("^http://", "https://"), "mangalib.me", "mangalib.org");
        if (uri != null && uri.getPath().startsWith(TITLE_PREFIX)) {
            String id = uri.getPath().substring(TITLE_PREFIX.length());
            if (ApiInputs.EXTERNAL_ID.matcher(id).matches()) return id;
        }
        throw badResponse("Invalid MangaLib URL");
    }

    private SourceTitle title(String id, String name, String english, String author, String cover,
                              String description, Integer year) {
        URI coverUrl = httpsUrl(cover, IMAGE_DOMAINS);
        return new SourceTitle(id(), id, displayName(), name, author, coverUrl == null ? null : coverUrl.toString(),
                description == null ? null : Jsoup.parse(description).text(), ORIGIN + TITLE_PREFIX + id,
                null, name, english, null, year);
    }

    private static ApiException titleMissing() {
        return ApiException.problem(HttpStatus.NOT_FOUND, "TITLE_NOT_FOUND", "Тайтл не найден.");
    }

    private static ApiException chapterMissing() {
        return ApiException.problem(HttpStatus.NOT_FOUND, "CHAPTER_NOT_FOUND", "Глава не найдена.");
    }

    private record Chapter(SourceChapter summary, String volume, String number, String branch, String team) {}
}
