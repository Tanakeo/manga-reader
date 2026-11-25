package io.github.mangareader.source;

import static io.github.mangareader.source.SourceModels.*;
import static io.github.mangareader.source.SourceScan.CatalogPage;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.mangareader.error.ApiException;
import io.github.mangareader.library.TitleRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/suwayomi")
@ConditionalOnProperty(name="kadr.suwayomi.enabled",havingValue="true")
public class SuwayomiSource implements MangaSource {
    private static final int MAX_IMAGE=20*1024*1024;
    private static final String PEPPER_SOURCE="279282683217001983";
    private static final License PEPPER_LICENSE=new License("CC BY 4.0","https://creativecommons.org/licenses/by/4.0/");
    private final RestClient client;
    private final JdbcClient jdbc;
    private final TitleRepository titles;
    private final String source;
    private final String language;
    private final String id;
    private final boolean local;
    private final com.github.benmanes.caffeine.cache.Cache<String,Details> cache =
            Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofSeconds(30)).build();
    private final com.github.benmanes.caffeine.cache.Cache<List<String>,ChapterPages> manifests =
            Caffeine.newBuilder().maximumSize(128).expireAfterWrite(Duration.ofMinutes(2)).build();

    SuwayomiSource(JdbcClient jdbc,TitleRepository titles,
                   @Value("${kadr.suwayomi.url:http://127.0.0.1:4567}") String address,
                   @Value("${kadr.suwayomi.instance:kadr-dev}") String instance,
                   @Value("${kadr.suwayomi.source:0}") String source,
                   @Value("${kadr.suwayomi.language:en}") String language,
                   @Value("${kadr.suwayomi.username:}") String username,
                   @Value("${kadr.suwayomi.password:}") String password) {
        URI origin=URI.create(address);
        if (!List.of("http","https").contains(origin.getScheme()) || !List.of("127.0.0.1","localhost","[::1]").contains(origin.getHost())
                || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null
                || !List.of("","/").contains(origin.getPath())) throw new IllegalArgumentException("Suwayomi must be a loopback origin");
        if (!source.matches("[0-9]+") || !List.of("ru","en").contains(language)) throw new IllegalArgumentException("Invalid Suwayomi source configuration");
        this.jdbc=jdbc;
        this.titles=titles;
        this.source=source;
        this.language=language;
        this.id="suwayomi-" + identity(instance,source).substring(0,20);
        local=source.equals("0");
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(12));
        var builder=RestClient.builder().baseUrl(address).requestFactory(factory);
        if (!username.isBlank()) builder.defaultHeaders(headers -> headers.setBasicAuth(username,password));
        client=builder.build();
    }

    @Override public String id(){return id;}
    @Override public String displayName(){
        if (local) return "Suwayomi · Local Source";
        if (source.equals(PEPPER_SOURCE)) return "Suwayomi · Pepper&Carrot";
        return "Suwayomi · " + source;
    }
    @Override public String websiteUrl(){return null;}
    @Override public List<String> supportedLanguages(){return List.of(language);}
    @Override public List<String> imageDomains(){return List.of();}

    @GetMapping("/sources")
    JsonNode discovery() { return graphql("{ sources { nodes { id name lang } } }",Map.of()).path("sources").path("nodes"); }

    @GetMapping("/search")
    List<SourceTitleSummary> search(@RequestParam(defaultValue="") String q) {
        if (q.length()>120) throw ApiException.problem(HttpStatus.BAD_REQUEST,"QUERY_TOO_LONG","Запрос слишком длинный.");
        return titles.syncCatalog(searchTitles(q)).stream().map(SourceTitle::summary).toList();
    }

    @Override public List<SourceTitle> searchTitles(String query) {
        return candidates(1,query).rows().stream().map(this::saveRef).toList();
    }

    @Override public CatalogPage catalogPage(String cursor) {
        int page=cursor==null ? 1 : Integer.parseInt(cursor);
        var result=page(page,"");
        var next=result.more() ? Integer.toString(page+1) : null;
        return new CatalogPage(result.rows().stream().map(this::saveRef).toList(),next,null,
                next!=null ? null : local ? "EXHAUSTED" : "SOURCE_LIMIT","Suwayomi popular page " + page);
    }

    private SourcePage page(int page,String query) {
        var input=new HashMap<String,Object>(Map.of("source",source,"page",page,"type",query.isBlank()?"POPULAR":"SEARCH"));
        if (!query.isBlank()) input.put("query",query);
        var result=graphql("""
                mutation($input:FetchSourceMangaInput!){fetchSourceManga(input:$input){
                  hasNextPage mangas{id title url sourceId author description realUrl}
                }}
                """,Map.of("input",input)).path("fetchSourceManga");
        if (!result.path("hasNextPage").isBoolean() || !result.path("mangas").isArray()) throw bad();
        var rows=new ArrayList<JsonNode>();
        for (JsonNode row : result.path("mangas")) {
            if (!source.equals(row.path("sourceId").asString()) || row.path("url").asString().isBlank()
                    || !row.path("id").isIntegralNumber()) throw bad();
            if (!source.equals(PEPPER_SOURCE) || !row.path("url").asString().startsWith("#")) rows.add(row);
        }
        return new SourcePage(rows,result.path("hasNextPage").asBoolean());
    }

    private SourcePage candidates(int page,String query) {
        try { return page(page,query); }
        catch (ApiException error) {
            if (query.isBlank() || !error.code().equals("SUWAYOMI_SEARCH_UNSUPPORTED")) throw error;
            var listing=page(page,"");
            String normalized=query.toLowerCase(Locale.ROOT);
            return new SourcePage(listing.rows().stream()
                    .filter(row -> row.path("title").asString().toLowerCase(Locale.ROOT).contains(normalized)).toList(),
                    listing.more());
        }
    }

    private SourceTitle saveRef(JsonNode manga) {
        String locator=manga.path("url").asString();
        String external=identity(id,locator);
        jdbc.sql("""
                INSERT INTO suwayomi_title_refs VALUES(:source,:id,:locator,:label,:service)
                ON CONFLICT(source_id,external_id) DO UPDATE SET label=excluded.label,service_id=excluded.service_id
                """).param("source",id).param("id",external).param("locator",locator)
                .param("label",manga.path("title").asString()).param("service",manga.path("id").asInt()).update();
        return summary(external,manga);
    }

    private SourceTitle summary(String key,JsonNode manga) {
        String url=PublicSourceClient.text(manga.path("realUrl"));
        if (url==null || !url.startsWith("https://")) url="/catalog/" + id + "/" + key;
        return new SourceTitle(id,key,displayName(),manga.path("title").asString(),
                PublicSourceClient.text(manga.path("author")),null,PublicSourceClient.text(manga.path("description")),
                url,source.equals(PEPPER_SOURCE) ? PEPPER_LICENSE : null,null,null,null,null);
    }

    @Override public SourceTitle getTitle(String external) { return details(external).title(); }
    @Override public List<SourceChapter> listChapters(String external) { return details(external).chapters().stream().map(Edition::chapter).toList(); }
    @Override public SourceScan.ChapterResult scanChapters(String external) {
        cache.invalidate(external);
        return MangaSource.super.scanChapters(external);
    }

    private Details details(String key) {
        return cache.get(key,external -> {
            var reference=jdbc.sql("SELECT source_locator,label,service_id FROM suwayomi_title_refs WHERE source_id=:source AND external_id=:id")
                    .param("source",id).param("id",external).query((r,n)->new Ref(r.getString(1),r.getString(2),r.getInt(3)))
                    .optional().orElseThrow(() -> missing("TITLE_NOT_FOUND"));
            int serviceId=reference.serviceId();
            if (!matches(serviceId,reference.locator())) {
                boolean found=false;
                for (int page=1;page<=10 && !found;page++) {
                    var candidates=candidates(page,reference.name());
                    var match=candidates.rows().stream().filter(row->reference.locator().equals(row.path("url").asString())).findFirst();
                    if (match.isPresent()) {
                        saveRef(match.get());
                        serviceId=match.get().path("id").asInt();
                        found=true;
                    } else if (!candidates.more()) break;
                }
                if (!found) throw missing("TITLE_NOT_FOUND");
            }
            var payload=graphql("""
                    mutation($id:Int!){fetchMangaAndChapters(input:{id:$id,fetchManga:true,fetchChapters:true}){
                      manga {id title url sourceId realUrl author description}
                      chapters {id name url realUrl chapterNumber scanlator sourceOrder pageCount uploadDate}
                    }}
                    """,Map.of("id",serviceId)).path("fetchMangaAndChapters");
            var manga=payload.path("manga");
            if (!reference.locator().equals(manga.path("url").asString()) || !source.equals(manga.path("sourceId").asString())
                    || !payload.path("chapters").isArray()) throw bad();
            var editions=new ArrayList<Edition>();
            var seen=new HashSet<String>();
            for (var row : payload.path("chapters")) {
                String locator=row.path("url").asString();
                if (locator.isBlank() || !row.path("id").isIntegralNumber()) throw bad();
                String chapterKey=identity(external,locator);
                if (!seen.add(chapterKey)) throw bad();
                long date=row.path("uploadDate").asLong(0);
                var chapter=new SourceChapter(chapterKey,row.path("chapterNumber").isNumber()?row.path("chapterNumber").decimalValue():null,
                        row.path("name").asString(),List.of(language),row.path("pageCount").asInt()>0?row.path("pageCount").asInt():null,
                        chapterKey,PublicSourceClient.text(row.path("scanlator")),date>0?Instant.ofEpochMilli(date):null);
                String url=PublicSourceClient.text(row.path("realUrl"));
                editions.add(new Edition(row.path("id").asInt(),chapter,row.path("sourceOrder").asInt(),
                        url != null && url.startsWith("https://") ? url : null));
            }
            editions.sort(Comparator.comparingInt(Edition::order).thenComparing(e->e.chapter().id()));
            return new Details(summary(external,manga),editions,serviceId);
        });
    }

    private boolean matches(int service,String locator) {
        var manga=graphql("{ manga(id:" + service + ") { url sourceId } }",Map.of(),true).path("manga");
        return source.equals(manga.path("sourceId").asString()) && locator.equals(manga.path("url").asString());
    }

    @Override public void refreshManifest(String chapter) {
        manifests.asMap().keySet().removeIf(key -> key.getLast().equals(chapter));
    }

    @Override public ChapterPages getPages(String title,String chapter,String lang) {
        if (!language.equals(lang)) throw ApiException.unavailableLanguage(List.of(language));
        return manifests.get(List.of(title,chapter),key -> {
            var data=details(title);
            for (int i=0;i<data.chapters().size();i++) {
                var edition=data.chapters().get(i);
                if (!edition.chapter().id().equals(chapter)) continue;
                var response=graphql("""
                       mutation($id:Int!){fetchChapterPages(input:{chapterId:$id}){pages}}
                       """,Map.of("id",edition.id())).path("fetchChapterPages").path("pages");
                if (!response.isArray() || response.isEmpty()) throw bad();
                var result=new ArrayList<Page>();
                for (var page : response) {
                    String url=page.asString();
                    String expected="/api/v1/manga/" + data.serviceId() + "/chapter/" + edition.order() + "/page/" + result.size();
                    if (!expected.equals(url)) throw bad();
                    result.add(new Page(result.size(),url));
                }
                return new ChapterPages(chapter,edition.chapter().title(),language,result.size(),
                        i==0?null:data.chapters().get(i-1).chapter().id(),i+1==data.chapters().size()?null:data.chapters().get(i+1).chapter().id(),
                        new Attribution(data.title().author(),displayName(),
                                edition.url()==null ? data.title().sourceUrl() : edition.url(),data.title().license()),result);
            }
            throw missing("CHAPTER_NOT_FOUND");
        });
    }

    @Override public PublicSourceClient.Image serverImage(String title,String chapter,String language,int index) {
        var pages=getPages(title,chapter,language).pages();
        if (index<0 || index>=pages.size()) throw missing("PAGE_NOT_FOUND");
        try {
            return client.get().uri(pages.get(index).imageUrl()).exchange((request,response)->{
                if (!response.getStatusCode().is2xxSuccessful()) throw bad();
                byte[] bytes=response.getBody().readNBytes(MAX_IMAGE+1);
                var type=response.getHeaders().getContentType();
                if (type!=null && "image".equals(type.getType()) && "jpg".equals(type.getSubtype())) {
                    type=MediaType.IMAGE_JPEG;
                }
                if (bytes.length>MAX_IMAGE || type==null || !PublicSourceClient.matchesImage(type,bytes)) throw bad();
                return new PublicSourceClient.Image(type,bytes);
            });
        } catch (RestClientException e) { throw ApiException.sourceUnavailable(e); }
    }

    private JsonNode graphql(String query,Map<String,?> variables) { return graphql(query,variables,false); }
    private JsonNode graphql(String query,Map<String,?> variables,boolean staleAllowed) {
        try {
            var result=client.post().uri("/api/graphql").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query",query,"variables",variables)).retrieve().body(JsonNode.class);
            if (result==null) throw bad();
            if (!staleAllowed && result.path("errors").isArray() && !result.path("errors").isEmpty()) {
                String problem=result.path("errors").path(0).path("message").asString();
                if (problem.startsWith("Exception while fetching data (/fetchSourceManga) : No search")) {
                    throw ApiException.problem(HttpStatus.UNPROCESSABLE_CONTENT,"SUWAYOMI_SEARCH_UNSUPPORTED",
                            "Расширение Suwayomi поддерживает только перечисление каталога.");
                }
                throw bad();
            }
            return result.path("data");
        } catch (RestClientException e) { throw ApiException.sourceUnavailable(e); }
    }

    private static String identity(String a,String b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((a + "\0" + b).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static ApiException bad() {return PublicSourceClient.badResponse("Unexpected Suwayomi response");}
    private static ApiException missing(String code){return ApiException.problem(HttpStatus.NOT_FOUND,code,"Выпуск Suwayomi не найден.");}
    record Ref(String locator,String name,int serviceId){}
    record Edition(int id,SourceChapter chapter,int order,String url){}
    record Details(SourceTitle title,List<Edition> chapters,int serviceId){}
    record SourcePage(List<JsonNode> rows,boolean more){}
}
