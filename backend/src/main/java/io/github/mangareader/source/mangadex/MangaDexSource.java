package io.github.mangareader.source.mangadex;

import static io.github.mangareader.source.mangadex.MangaDexClient.WEBSITE;
import static io.github.mangareader.source.mangadex.MangaDexClient.bad;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.mangareader.error.ApiException;
import io.github.mangareader.library.TitleTagRepository;
import io.github.mangareader.source.MangaSource;
import io.github.mangareader.source.SourceModels.Attribution;
import io.github.mangareader.source.SourceModels.ChapterPages;
import io.github.mangareader.source.SourceModels.Page;
import io.github.mangareader.source.SourceModels.SourceChapter;
import io.github.mangareader.source.SourceModels.SourceTitle;
import io.github.mangareader.source.SourceModels.Tag;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class MangaDexSource implements MangaSource {
    private static final String ID = "mangadex";
    private static final Set<String> LANGUAGES = Set.of("en", "ru");
    private static final Set<String> RATINGS = Set.of("safe", "suggestive");
    private static final Set<String> STATUSES = Set.of("ongoing", "completed", "hiatus", "cancelled");
    private static final Pattern UUID = Pattern.compile("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{32,64}");
    private static final Pattern FILE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,249}");
    private static final int SEARCH_LIMIT = 24;
    private static final int FEED_LIMIT = 500;
    private static final int MAX_FEED_RESULTS = 10_000;
    private static final List<String> SORTS = List.of("relevance", "year_desc", "year_asc", "latest_chapter_desc");

    private final MangaDexClient client;
    private final TitleTagRepository tagRepository;
    private final Set<String> excludedGroups;
    private final Cache<Search, List<SourceTitle>> searches = cache(256, 2);
    private final Cache<String, List<Tag>> tags = cache(1, 60);
    private final Cache<String, SourceTitle> titles = cache(256, 5);
    private final Cache<String, List<Edition>> feeds = cache(96, 3);
    private final Cache<String, List<URI>> manifests = cache(128, 3);

    public MangaDexSource(MangaDexClient client, TitleTagRepository tagRepository,
                          @Value("${MANGADEX_EXCLUDED_GROUP_IDS:}") String excludedGroups) {
        this.client = client;
        this.tagRepository = tagRepository;
        this.excludedGroups = Arrays.stream(excludedGroups.split(",")).map(String::strip)
                .filter(id -> !id.isEmpty())
                .map(id -> id.toLowerCase(Locale.ROOT))
                .peek(id -> {
                    if (!UUID.matcher(id).matches()) {
                        throw new IllegalArgumentException("Invalid UUID in MANGADEX_EXCLUDED_GROUP_IDS");
                    }
                }).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "MangaDex";
    }

    @Override
    public String websiteUrl() {
        return WEBSITE;
    }

    @Override
    public List<SourceTitle> searchTitles(String query) {
        return searchTitles(query, List.of(), "");
    }

    @Override
    public List<SourceTitle> searchTitles(String query, List<String> tagIds, String sort) {
        if (!tagIds.isEmpty()) {
            var known = catalogTags().stream().map(Tag::id).collect(Collectors.toSet());
            var invalid = tagIds.stream().filter(id -> !known.contains(id)).toList();
            if (!invalid.isEmpty()) {
                throw ApiException.invalidTags(invalid);
            }
        }
        return searches.get(new Search(query, tagIds, sort), this::search);
    }

    @Override
    public boolean supportsTags() {
        return true;
    }

    @Override
    public List<Tag> catalogTags() {
        return tags.get(ID, ignored -> {
            var dictionary = readTags(dataArray(client.get(uri -> uri.path("/manga/tag").build())));
            if (dictionary == null) throw bad("Invalid MangaDex tag dictionary");
            tagRepository.saveDictionary(ID, dictionary, Instant.now());
            return dictionary;
        });
    }

    List<SourceTitle> fetchByIds(List<String> ids) {
        var response = client.get(uri -> uri.path("/manga").queryParam("limit", ids.size())
                .queryParam("ids[]", ids).queryParam("includes[]", "cover_art", "author")
                .queryParam("contentRating[]", "safe", "suggestive").build());
        return elements(dataArray(response)).filter(this::supportedTitle).map(this::readTitle).toList();
    }

    @Override
    public List<String> catalogSorts() {
        return SORTS;
    }

    @Override
    public SourceTitle getTitle(String id) {
        return titles.get(requireUuid(id, "TITLE_NOT_FOUND"), this::loadTitle);
    }

    @Override
    public List<SourceChapter> listChapters(String id) {
        return editions(id).stream().map(Edition::chapter).toList();
    }

    @Override
    public int chapterCount(String id) {
        return Math.toIntExact(editions(id).stream().map(Edition::identity).distinct().count());
    }

    @Override
    public ChapterPages getPages(String titleId, String chapterId, String language) {
        List<Edition> editions = editions(titleId);
        Edition selected = findEdition(editions, chapterId, language);
        List<URI> images = manifests.get(selected.chapter().id(), this::loadManifest);
        SourceTitle title = getTitle(titleId);
        String prefix = "/api/v1/sources/mangadex/titles/" + title.externalTitleId()
                + "/chapters/" + selected.chapter().id() + "/images/";
        List<Page> pages = IntStream.range(0, images.size())
                .mapToObj(index -> new Page(index, prefix + index + "?language=" + language)).toList();
        var attribution = new Attribution(title.author(), selected.credits(),
                WEBSITE + "/chapter/" + selected.chapter().id(), null);
        return new ChapterPages(selected.chapter().id(), selected.chapter().title(), language, pages.size(),
                neighbour(editions, selected, -1), neighbour(editions, selected, 1), attribution, pages);
    }

    MangaDexClient.Image getImage(String titleId, String chapterId, String language, int index) {
        String id = findEdition(editions(titleId), chapterId, language).chapter().id();
        List<URI> manifest = manifests.get(id, this::loadManifest);
        MangaDexClient.Image image = client.image(imageUri(manifest, index));
        if (image == null) {
            manifests.asMap().remove(id, manifest);
            image = client.image(imageUri(manifests.get(id, this::loadManifest), index));
        }
        if (image == null) {
            throw ApiException.sourceUnavailable(new IllegalStateException("MangaDex image unavailable"));
        }
        return image;
    }

    MangaDexClient.Image getCover(String titleId, String fileName) {
        String id = requireUuid(titleId, "TITLE_NOT_FOUND");
        if (!FILE.matcher(fileName).matches()) {
            throw ApiException.problem(HttpStatus.NOT_FOUND, "COVER_NOT_FOUND", "Обложка не найдена.");
        }
        URI uri = URI.create("https://uploads.mangadex.org/covers/" + id + "/" + fileName + ".512.jpg");
        MangaDexClient.Image image = client.image(uri);
        if (image == null) {
            throw ApiException.sourceUnavailable(new IllegalStateException("MangaDex cover unavailable"));
        }
        return image;
    }

    private List<SourceTitle> search(Search search) {
        String query = search.query();
        JsonNode result = client.get(uri -> {
            uri.path("/manga").queryParam("limit", SEARCH_LIMIT)
                    .queryParam("includes[]", "cover_art", "author")
                    .queryParam("contentRating[]", "safe", "suggestive")
                    .queryParam("availableTranslatedLanguage[]", "en", "ru")
                    .queryParam("hasAvailableChapters", true);
            if (!search.tags().isEmpty()) {
                uri.queryParam("includedTags[]", search.tags()).queryParam("includedTagsMode", "AND");
            }
            String order = switch (search.sort()) {
                case "year_desc", "year_asc" -> "year";
                case "latest_chapter_desc" -> "latestUploadedChapter";
                case "relevance" -> query.isBlank() ? "followedCount" : "relevance";
                default -> query.isBlank() ? "followedCount" : null;
            };
            if (order != null) {
                uri.queryParam("order[" + order + "]", "year_asc".equals(search.sort()) ? "asc" : "desc");
            }
            return query.isBlank() ? uri.build() : uri.queryParam("title", "{query}").build(query);
        });
        return elements(dataArray(result)).filter(this::supportedTitle).map(this::readTitle)
                .peek(title -> titles.asMap().putIfAbsent(title.externalTitleId(), title)).toList();
    }

    private SourceTitle loadTitle(String id) {
        JsonNode response = client.get(uri -> uri.path("/manga/" + id)
                .queryParam("includes[]", "cover_art", "author").build());
        if (response == null || !supportedTitle(response.path("data"))) {
            throw notFound("TITLE_NOT_FOUND");
        }
        return readTitle(response.path("data"));
    }

    private boolean supportedTitle(JsonNode row) {
        return UUID.matcher(row.path("id").asString()).matches()
                && RATINGS.contains(row.path("attributes").path("contentRating").asString());
    }

    private SourceTitle readTitle(JsonNode row) {
        String id = row.path("id").asString();
        JsonNode attributes = row.path("attributes");
        String title = localized(attributes.path("title"));
        String titleRu = translatedTitle(attributes, "ru");
        String titleEn = translatedTitle(attributes, "en");
        String status = text(attributes.path("status"));
        int year = attributes.path("year").asInt(0);
        String cover = relations(row, "cover_art").map(node -> node.path("attributes").path("fileName").asString())
                .filter(file -> FILE.matcher(file).matches()).findFirst().orElse(null);
        List<Tag> snapshot = readTags(attributes.path("tags"));
        return new SourceTitle(ID, id, displayName(), title == null ? "Без названия" : title, names(row, "author"),
                cover == null ? null : "/api/v1/sources/mangadex/titles/" + id + "/covers/" + cover,
                localized(attributes.path("description")), WEBSITE + "/title/" + id, null, titleRu, titleEn,
                status != null && STATUSES.contains(status) ? status : null, year > 0 ? year : null,
                snapshot == null ? List.of() : snapshot, version(attributes),
                snapshot == null ? null : Instant.now());
    }

    private static List<Tag> readTags(JsonNode entries) {
        if (!entries.isArray()) return null;
        var tags = new LinkedHashMap<String, Tag>();
        for (JsonNode row : entries) {
            String id = row.path("id").asString();
            JsonNode attributes = row.path("attributes");
            String group = text(attributes.path("group"));
            Map<String, String> names = new java.util.TreeMap<>();
            attributes.path("name").propertyStream().forEach(entry -> {
                String name = text(entry.getValue());
                if (name != null) names.put(entry.getKey(), name);
            });
            if (!UUID.matcher(id).matches() || group == null || names.isEmpty()) return null;
            tags.put(id, new Tag(id, group, Map.copyOf(names), version(attributes)));
        }
        return tags.values().stream().sorted(Comparator.comparing(Tag::group)
                .thenComparing(tag -> tag.names().getOrDefault("en", tag.names().entrySet().stream()
                        .min(Map.Entry.comparingByKey()).orElseThrow().getValue()))
                .thenComparing(Tag::id)).toList();
    }

    private static Integer version(JsonNode attributes) {
        int value = attributes.path("version").asInt(-1);
        return value < 0 ? null : value;
    }

    private List<Edition> editions(String id) {
        return feeds.get(getTitle(id).externalTitleId(), this::loadFeed);
    }

    private List<Edition> loadFeed(String id) {
        var unique = new LinkedHashMap<String, Edition>();
        for (int offset = 0; offset < MAX_FEED_RESULTS; offset += FEED_LIMIT) {
            int start = offset;
            JsonNode response = client.get(uri -> uri.path("/manga/" + id + "/feed")
                    .queryParam("limit", FEED_LIMIT).queryParam("offset", start)
                    .queryParam("translatedLanguage[]", "en", "ru")
                    .queryParam("contentRating[]", "safe", "suggestive")
                    .queryParam("includes[]", "scanlation_group")
                    .queryParam("order[volume]", "asc").queryParam("order[chapter]", "asc")
                    .queryParam("order[publishAt]", "asc").build());
            if (response == null) {
                throw notFound("TITLE_NOT_FOUND");
            }
            JsonNode data = dataArray(response);
            for (JsonNode row : data) {
                Edition edition = readEdition(row);
                if (edition != null) {
                    unique.putIfAbsent(edition.chapter().id(), edition);
                }
            }
            if (data.size() < FEED_LIMIT || start + data.size() >= response.path("total").asInt()) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    private Edition readEdition(JsonNode row) {
        JsonNode attributes = row.path("attributes");
        String id = row.path("id").asString();
        String language = attributes.path("translatedLanguage").asString();
        int pageCount = attributes.path("pages").asInt();
        if (!UUID.matcher(id).matches() || !LANGUAGES.contains(language) || pageCount < 1
                || text(attributes.path("externalUrl")) != null
                || relations(row, "scanlation_group").anyMatch(group -> excludedGroups.contains(group.path("id").asString()))) {
            return null;
        }
        String number = text(attributes.path("chapter"));
        String title = text(attributes.path("title"));
        if (title == null) {
            title = number == null ? "Дополнительная глава" : "Глава " + number;
        }
        var chapter = new SourceChapter(id, chapterNumber(number), title, List.of(language), pageCount);
        return new Edition(chapter, text(attributes.path("volume")), language, names(row, "scanlation_group"));
    }

    private Edition findEdition(List<Edition> editions, String id, String language) {
        String chapterId = requireUuid(id, "CHAPTER_NOT_FOUND");
        Edition edition = editions.stream().filter(item -> item.chapter().id().equals(chapterId)).findFirst()
                .orElseThrow(() -> notFound("CHAPTER_NOT_FOUND"));
        if (!edition.language().equals(language)) {
            throw ApiException.unavailableLanguage(edition.chapter().languages());
        }
        return edition;
    }

    private String neighbour(List<Edition> editions, Edition selected, int step) {
        for (int i = editions.indexOf(selected) + step; i >= 0 && i < editions.size(); i += step) {
            Edition candidate = editions.get(i);
            if (candidate.language().equals(selected.language()) && !selected.sameNumber(candidate)) {
                return candidate.chapter().id();
            }
        }
        return null;
    }

    private List<URI> loadManifest(String id) {
        JsonNode response = client.get(uri -> uri.path("/at-home/server/" + id).queryParam("forcePort443", true).build());
        if (response == null) {
            throw notFound("CHAPTER_NOT_FOUND");
        }
        String baseUrl = response.path("baseUrl").asString();
        URI base;
        try {
            base = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw bad("Invalid MangaDex image address");
        }
        String host = base.getHost();
        if (!"https".equals(base.getScheme()) || host == null
                || !(host.endsWith(".mangadex.network") || host.endsWith(".mangadex.org"))
                || (base.getPort() != -1 && base.getPort() != 443) || base.getRawUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw bad("Unsupported MangaDex image host");
        }
        JsonNode chapter = response.path("chapter");
        String hash = chapter.path("hash").asString();
        JsonNode files = chapter.path("dataSaver");
        String quality = "data-saver";
        if (!files.isArray() || files.isEmpty()) {
            files = chapter.path("data");
            quality = "data";
        }
        if (!HASH.matcher(hash).matches() || !files.isArray() || files.isEmpty()) {
            throw bad("Invalid MangaDex image manifest");
        }
        String prefix = baseUrl.replaceFirst("/+$", "") + "/" + quality + "/" + hash + "/";
        var urls = new ArrayList<URI>();
        for (JsonNode file : files) {
            if (!FILE.matcher(file.asString()).matches()) {
                throw bad("Invalid MangaDex image filename");
            }
            urls.add(URI.create(prefix + file.asString()));
        }
        return List.copyOf(urls);
    }

    private URI imageUri(List<URI> manifest, int index) {
        if (index < 0 || index >= manifest.size()) {
            throw ApiException.problem(HttpStatus.NOT_FOUND, "PAGE_NOT_FOUND", "Страница не найдена.");
        }
        return manifest.get(index);
    }

    private static JsonNode dataArray(JsonNode response) {
        if (response == null || !response.path("data").isArray()) {
            throw bad("MangaDex returned an invalid collection");
        }
        return response.path("data");
    }

    private static Stream<JsonNode> relations(JsonNode row, String type) {
        return elements(row.path("relationships")).filter(item -> type.equals(item.path("type").asString()));
    }

    private static Stream<JsonNode> elements(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }

    private static String names(JsonNode row, String type) {
        String names = relations(row, type).map(item -> text(item.path("attributes").path("name")))
                .filter(Objects::nonNull).distinct().collect(Collectors.joining(", "));
        return names.isEmpty() ? null : names;
    }

    private static String localized(JsonNode values) {
        return Stream.concat(Stream.of(values.path("ru"), values.path("en")),
                        values.propertyStream().map(entry -> entry.getValue()))
                .map(MangaDexSource::text).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static String translatedTitle(JsonNode attributes, String language) {
        String primary = text(attributes.path("title").path(language));
        return primary != null ? primary : elements(attributes.path("altTitles"))
                .map(alternative -> text(alternative.path(language)))
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static String text(JsonNode value) {
        return value.isString() && !value.stringValue().isBlank() ? value.stringValue() : null;
    }

    private static BigDecimal chapterNumber(String value) {
        if (value != null && value.matches("[0-9]{1,8}(?:\\.[0-9]{1,8})?")) {
            return new BigDecimal(value);
        }
        return null;
    }

    private static String requireUuid(String value, String code) {
        String id = value.toLowerCase(Locale.ROOT);
        if (!UUID.matcher(id).matches()) {
            throw notFound(code);
        }
        return id;
    }

    private static ApiException notFound(String code) {
        return ApiException.problem(HttpStatus.NOT_FOUND, code,
                code.equals("TITLE_NOT_FOUND") ? "Тайтл не найден." : "Глава не найдена.");
    }

    private static <K, V> Cache<K, V> cache(int entries, int minutes) {
        return Caffeine.newBuilder().maximumSize(entries).expireAfterWrite(Duration.ofMinutes(minutes)).build();
    }

    private record Edition(SourceChapter chapter, String volume, String language, String groupNames) {
        ChapterIdentity identity() {
            BigDecimal number = chapter.number();
            return number == null ? new ChapterIdentity(null, null, chapter.id())
                    : new ChapterIdentity(volume, number.stripTrailingZeros(), null);
        }

        String credits() {
            return groupNames == null ? "Источник · MangaDex" : "Источник · MangaDex. Перевод · " + groupNames;
        }

        boolean sameNumber(Edition other) {
            return identity().equals(other.identity());
        }
    }

    private record ChapterIdentity(String volume, BigDecimal number, String unnumberedId) {}

    private record Search(String query, List<String> tags, String sort) {}
}
