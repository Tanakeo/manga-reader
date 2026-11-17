package io.github.mangareader.source.peppercarrot;

import static io.github.mangareader.source.peppercarrot.PepperCarrotClient.ORIGIN;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.mangareader.error.ApiException;
import io.github.mangareader.source.MangaSource;
import io.github.mangareader.source.SourceModels.Attribution;
import io.github.mangareader.source.SourceModels.ChapterPages;
import io.github.mangareader.source.SourceModels.License;
import io.github.mangareader.source.SourceModels.Page;
import io.github.mangareader.source.SourceModels.SourceChapter;
import io.github.mangareader.source.SourceModels.SourceTitle;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PepperCarrotSource implements MangaSource {
    private static final String INDEX_PATH = "/0_sources/episodes.json";
    private static final String AUTHOR = "David Revoy";
    private static final License LICENSE = new License("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/");
    private static final SourceTitle TITLE = new SourceTitle(
            "peppercarrot", "pepper-and-carrot", "Pepper&Carrot", "Pepper&Carrot", AUTHOR,
            ORIGIN + "/0_sources/ep01_Potion-of-Flight/low-res/en_Pepper-and-Carrot_by-David-Revoy_E01.jpg",
            "Веб-комикс о молодой ведьме Пеппер и её коте Морковке.",
            ORIGIN + "/en/webcomics/peppercarrot.html", LICENSE, "Перчинка и Морквик", "Pepper&Carrot", null, 2014);
    private static final List<String> SUPPORTED_LANGUAGES = List.of("en", "ru");
    private static final Pattern EPISODE_ID = Pattern.compile("ep[0-9]{2,6}_[a-zA-Z0-9-]+");
    private static final TypeReference<List<Episode>> INDEX_TYPE = new TypeReference<>() {};

    private final PepperCarrotClient client;
    private final JsonMapper json;
    private final Cache<String, List<Episode>> indexCache = Caffeine.newBuilder()
            .maximumSize(1).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<ChapterKey, ChapterPages> pagesCache = Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(Duration.ofMinutes(5)).build();

    public PepperCarrotSource(PepperCarrotClient client, JsonMapper json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public String id() {
        return TITLE.sourceId();
    }

    @Override
    public String displayName() {
        return TITLE.sourceName();
    }

    @Override
    public String websiteUrl() {
        return ORIGIN;
    }

    @Override
    public List<SourceTitle> searchTitles(String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return TITLE.titleEn().toLowerCase(Locale.ROOT).contains(needle)
                || TITLE.titleRu().toLowerCase(Locale.ROOT).contains(needle) ? List.of(TITLE) : List.of();
    }

    @Override
    public SourceTitle getTitle(String externalTitleId) {
        if (!TITLE.externalTitleId().equals(externalTitleId)) {
            throw ApiException.problem(HttpStatus.NOT_FOUND, "TITLE_NOT_FOUND", "Тайтл не найден.");
        }
        return TITLE;
    }

    @Override
    public List<SourceChapter> listChapters(String externalTitleId) {
        getTitle(externalTitleId);
        return episodes().stream().filter(episode -> !episode.languages().isEmpty())
                .map(Episode::toChapter).toList();
    }

    @Override
    public ChapterPages getPages(String externalTitleId, String chapterId, String language) {
        getTitle(externalTitleId);
        List<Episode> episodes = episodes();
        Episode episode = episodes.stream().filter(item -> item.name().equals(chapterId)).findFirst()
                .orElseThrow(() -> ApiException.problem(HttpStatus.NOT_FOUND, "CHAPTER_NOT_FOUND", "Глава не найдена."));
        if (!episode.languages().contains(language)) {
            throw ApiException.unavailableLanguage(episode.languages());
        }
        return pagesCache.get(new ChapterKey(chapterId, language), key -> loadPages(episode, language, episodes));
    }

    private List<Episode> episodes() {
        return indexCache.get(INDEX_PATH, this::loadEpisodes);
    }

    private List<Episode> loadEpisodes(String path) {
        String body = client.get(path);
        List<Episode> episodes;
        try {
            episodes = json.readValue(body, INDEX_TYPE);
        } catch (JacksonException exception) {
            throw ApiException.sourceBadResponse(exception);
        }
        if (episodes == null || episodes.isEmpty()) {
            throw badResponse("Empty episode index");
        }
        var names = new HashSet<String>();
        for (Episode episode : episodes) {
            if (episode == null || episode.name() == null || !EPISODE_ID.matcher(episode.name()).matches()
                    || episode.totalPages() == null || episode.totalPages() < 0 || episode.totalPages() > 9999
                    || episode.translatedLanguages() == null || !names.add(episode.name())) {
                throw badResponse("Invalid or duplicated episode in the index");
            }
        }
        return episodes.stream().sorted(Comparator.comparingInt(Episode::number)).toList();
    }

    private ChapterPages loadPages(Episode episode, String language, List<Episode> episodes) {
        String path = "/" + language + "/webcomic/" + episode.name() + ".html";
        String sourceUrl = ORIGIN + path;
        Document document = Jsoup.parse(client.get(path), sourceUrl);
        String imagePrefix = ORIGIN + "/0_sources/" + episode.name() + "/low-res/" + language
                + "_Pepper-and-Carrot_by-David-Revoy_E" + episode.numberToken() + "P";
        Pattern pageUrl = Pattern.compile(Pattern.quote(imagePrefix) + "([0-9]{2,4})\\.(?:jpg|gif)");
        List<Page> pages = document.select(".webcomic-page img[src]").stream()
                .map(img -> img.absUrl("src"))
                .map(url -> pageUrl.matcher(url))
                .filter(matcher -> matcher.matches())
                .map(matcher -> new Page(Integer.parseInt(matcher.group(1)), matcher.group()))
                .sorted(Comparator.comparingInt(Page::index))
                .toList();
        if (pages.size() != episode.pageCount()) {
            throw badResponse("Unexpected page count for " + path);
        }
        for (int index = 0; index < pages.size(); index++) {
            if (pages.get(index).index() != index) {
                throw badResponse("Unexpected page numbering for " + path);
            }
        }

        List<Episode> translated = episodes.stream().filter(item -> item.languages().contains(language)).toList();
        int position = translated.indexOf(episode);
        String previous = position > 0 ? translated.get(position - 1).name() : null;
        String next = position + 1 < translated.size() ? translated.get(position + 1).name() : null;
        String title = document.title().replaceFirst("^(Episode|Эпизод)\\s+\\d+\\s*:\\s*", "")
                .replaceFirst("\\s+-\\s+Pepper&Carrot$", "").strip();
        String credits = document.select(".translacredit").text();
        var attribution = new Attribution(AUTHOR, credits, sourceUrl, LICENSE);
        return new ChapterPages(episode.name(), title.isEmpty() ? episode.displayName() : title, language,
                pages.size(), previous, next, attribution, pages);
    }

    private ApiException badResponse(String reason) {
        return ApiException.sourceBadResponse(new IllegalStateException(reason));
    }

    private record ChapterKey(String chapterId, String language) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Episode(String name, @JsonProperty("total_pages") Integer totalPages,
                           @JsonProperty("translated_languages") List<String> translatedLanguages) {
        String numberToken() {
            return name.substring(2, name.indexOf('_'));
        }

        int number() {
            return Integer.parseInt(numberToken());
        }

        String displayName() {
            return name.substring(name.indexOf('_') + 1).replace('-', ' ');
        }

        int pageCount() {
            return totalPages + 1;
        }

        List<String> languages() {
            return SUPPORTED_LANGUAGES.stream().filter(translatedLanguages::contains).toList();
        }

        SourceChapter toChapter() {
            return new SourceChapter(name, BigDecimal.valueOf(number()), displayName(), languages(), pageCount());
        }
    }
}
