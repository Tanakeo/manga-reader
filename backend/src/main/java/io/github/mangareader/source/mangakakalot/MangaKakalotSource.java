package io.github.mangareader.source.mangakakalot;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class MangaKakalotSource implements MangaSource {
    private static final String ORIGIN = "https://www.kakamanga.com";
    private static final String[] IMAGE_DOMAINS = {"2xstorage.com", "kakamanga.com"};
    private static final String CARDS = ".list-comic-item-wrap, .list-truyen-item-wrap, .panel_story_list .story_item";
    private static final List<String> LANGUAGES = List.of("en");
    private static final Pattern TITLE_PATH = Pattern.compile("/manga/([A-Za-z0-9][A-Za-z0-9._~-]{0,199})/?");
    private static final Pattern PAGE_PATH = Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9_./%-]*");

    private final PublicSourceClient client;
    private final Cache<String, List<SourceTitle>> catalog =
            Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, List<SourceTitle>> publicFeed =
            Caffeine.newBuilder().maximumSize(1).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, SourceTitle> titles =
            Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(10)).build();
    private final Cache<String, List<SourceChapter>> chapters =
            Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(5)).build();

    public MangaKakalotSource(PublicSourceClient client) {
        this.client = client;
    }

    @Override public String id() { return "mangakakalot"; }
    @Override public String displayName() { return "MangaKakalot"; }
    @Override public String websiteUrl() { return ORIGIN; }

    @Override
    public List<SourceTitle> searchTitles(String query) {
        return catalog.get(query.strip().toLowerCase(Locale.ROOT), this::search);
    }

    private List<SourceTitle> search(String query) {
        if (query.isBlank()) return popular();
        String slug = query.replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_|_$", "");
        if (slug.isEmpty()) return List.of();
        try {
            URI search = url(ORIGIN, "search", "story", slug).queryParam("page", 1).build().encode().toUri();
            return parseCatalog(client.html(search, null));
        } catch (ApiException exception) {
            if (!"SOURCE_ACCESS_DENIED".equals(exception.code())) throw exception;
            var matches = popular().stream()
                    .filter(title -> title.title().toLowerCase(Locale.ROOT).contains(query)).toList();
            if (matches.isEmpty()) throw exception;
            return matches;
        }
    }

    private List<SourceTitle> popular() {
        return publicFeed.get("", key -> {
            var uri = url(ORIGIN, "manga-list", "hot-manga").queryParam("page", 1).build().toUri();
            var items = parseCatalog(client.html(uri, null));
            if (items.isEmpty()) throw badResponse("Missing popular manga cards");
            return items;
        });
    }

    private List<SourceTitle> parseCatalog(Document document) {
        var items = new LinkedHashMap<String, SourceTitle>();
        for (Element card : document.select(CARDS)) {
            Element link = card.selectFirst("h3 a[href], a.name[href], a[data-id][href]");
            if (link == null) continue;
            URI href = httpsUrl(link.absUrl("href"), "kakamanga.com");
            if (href == null) continue;
            var match = TITLE_PATH.matcher(href.getPath());
            String title = link.text().isBlank() ? link.attr("title") : link.text();
            if (!match.matches() || title.isBlank()) continue;
            String id = match.group(1);
            Element img = card.selectFirst("img");
            URI cover = img == null ? null : httpsUrl(img.absUrl(img.hasAttr("data-src") ? "data-src" : "src"),
                    IMAGE_DOMAINS);
            items.putIfAbsent(id, title(id, title, null, cover, null, null));
        }
        return List.copyOf(items.values());
    }

    @Override
    public SourceTitle getTitle(String id) {
        return titles.get(id, key -> {
            URI uri = url(ORIGIN, "manga", key).build().encode().toUri();
            Document document = client.html(uri, MangaKakalotSource::titleMissing);
            var heading = document.selectFirst(".manga-info-top h1, .panel-story-info h1");
            if (heading == null || heading.text().isBlank()) throw badResponse("Title name missing");
            var author = document.selectFirst(".manga-info-top li:matchesOwn((?i)^author), td:containsOwn(author) + td");
            var cover = document.selectFirst(".manga-info-pic img, .info-image img");
            var description = document.selectFirst("#contentBox, #noidungm, #panel-story-info-description");
            if (description != null) description.select("h2, script").remove();
            var status = document.selectFirst(".manga-info-top li:matchesOwn((?i)^status), td:containsOwn(status) + td");
            String authorName = author == null ? null
                    : author.text().replaceFirst("(?i)^authors?\\s*(?:\\(s\\))?\\s*:\\s*", "");
            String publicationStatus = status == null ? null
                    : status.text().replaceFirst("(?i)^status\\s*:\\s*", "").toLowerCase(Locale.ROOT);
            return title(key, heading.text(), authorName, cover == null ? null : httpsUrl(cover.absUrl("src"), IMAGE_DOMAINS),
                    description == null ? null : description.text(), publicationStatus);
        });
    }

    @Override
    public List<SourceChapter> listChapters(String id) {
        return chapters.get(id, key -> {
            URI uri = url(ORIGIN, "api", "manga", key, "chapters").queryParam("limit", -1).build().encode().toUri();
            JsonNode response = client.json(uri, null, MangaKakalotSource::titleMissing);
            JsonNode items = response.path("data").path("chapters");
            if (!response.path("success").asBoolean() || !items.isArray()) throw badResponse("Missing chapters");
            var ordered = new LinkedHashMap<String, SourceChapter>();
            for (JsonNode item : items) {
                String slug = text(item.path("chapter_slug"));
                String name = text(item.path("chapter_name"));
                if (slug == null || name == null || !ApiInputs.EXTERNAL_ID.matcher(slug).matches()) continue;
                JsonNode number = item.path("chapter_num");
                ordered.putIfAbsent(slug, new SourceChapter(slug, number.isNumber() ? number.decimalValue() : null,
                        name, LANGUAGES, 0));
            }
            return ordered.values().stream()
                    .sorted(Comparator.comparing(SourceChapter::number, Comparator.nullsLast(BigDecimal::compareTo)))
                    .toList();
        });
    }

    @Override
    public ChapterPages getPages(String id, String chapterId, String language) {
        if (!"en".equalsIgnoreCase(language)) throw ApiException.unavailableLanguage(LANGUAGES);
        var chapters = listChapters(id);
        int index = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).id().equals(chapterId)) { index = i; break; }
        }
        if (index < 0) throw chapterMissing();
        URI page = url(ORIGIN, "manga", id, chapterId).build().encode().toUri();
        var images = images(client.html(page, MangaKakalotSource::chapterMissing));
        return new ChapterPages(chapterId, chapters.get(index).title(), "en", images.size(),
                index > 0 ? chapters.get(index - 1).id() : null,
                index + 1 < chapters.size() ? chapters.get(index + 1).id() : null,
                new Attribution(getTitle(id).author(), "Источник — MangaKakalot.", page.toString(), null), images);
    }

    private List<Page> images(Document document) {
        var images = new ArrayList<Page>();
        for (Element script : document.select("script:containsData(chapterImages)")) {
            JsonNode paths = scriptArray(script.data(), "chapterImages");
            if (paths == null || paths.isEmpty()) continue;
            JsonNode cdns = scriptArray(script.data(), "cdns");
            URI cdn = cdns == null ? null : httpsUrl(text(cdns.path(0)), IMAGE_DOMAINS);
            if (cdn == null) throw badResponse("Missing chapter image host");
            for (JsonNode path : paths) {
                String value = text(path);
                if (value == null || !PAGE_PATH.matcher(value).matches()
                        || value.contains("..") || value.contains("%")) throw badResponse("Invalid image path");
                images.add(new Page(images.size(), cdn.resolve("/" + value).toString()));
            }
            return List.copyOf(images);
        }
        for (Element img : document.select(".container-chapter-reader > img")) {
            URI image = httpsUrl(img.absUrl(img.hasAttr("data-src") ? "data-src" : "src"), IMAGE_DOMAINS);
            if (image == null) throw badResponse("Invalid chapter image");
            images.add(new Page(images.size(), image.toString()));
        }
        if (images.isEmpty()) throw badResponse("Chapter contains no pages");
        return List.copyOf(images);
    }

    private JsonNode scriptArray(String script, String name) {
        var match = Pattern.compile("\\b" + name + "\\s*=\\s*(\\[[^\\]]*\\])").matcher(script);
        return match.find() ? client.parse(match.group(1)) : null;
    }

    private SourceTitle title(String id, String title, String author, URI cover, String description, String status) {
        return new SourceTitle(id(), id, displayName(), title, author, cover == null ? null : cover.toString(),
                description, ORIGIN + "/manga/" + id, null, null, title, status, null);
    }

    private static ApiException titleMissing() {
        return ApiException.problem(HttpStatus.NOT_FOUND, "TITLE_NOT_FOUND", "Тайтл не найден.");
    }

    private static ApiException chapterMissing() {
        return ApiException.problem(HttpStatus.NOT_FOUND, "CHAPTER_NOT_FOUND", "Глава не найдена.");
    }
}
