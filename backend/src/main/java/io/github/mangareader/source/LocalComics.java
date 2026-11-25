package io.github.mangareader.source;

import static io.github.mangareader.source.SourceModels.*;

import io.github.mangareader.catalog.CatalogSyncService;
import io.github.mangareader.error.ApiException;
import io.github.mangareader.library.TitleRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/local-comics")
public class LocalComics implements MangaSource {
    private static final int MAX_PAGE = 20 * 1024 * 1024;
    private static final long MAX_CHAPTER = 256L * 1024 * 1024;
    private static final int MAX_FILES = 1000;
    private final JdbcClient jdbc;
    private final TitleRepository titles;
    private final org.springframework.beans.factory.ObjectProvider<CatalogSyncService> queue;
    private final Path root;
    private final Path importRoot;

    LocalComics(JdbcClient jdbc, TitleRepository titles,
                org.springframework.beans.factory.ObjectProvider<CatalogSyncService> queue,
                @Value("${KADR_LOCAL_COMICS_DIR:./data/local-comics}") String root,
                @Value("${KADR_LOCAL_IMPORT_DIR:}") String importRoot) {
        this.jdbc = jdbc;
        this.titles = titles;
        this.queue = queue;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.importRoot = importRoot.isBlank() ? null : Path.of(importRoot).toAbsolutePath().normalize();
    }

    @Override public String id() { return "local"; }
    @Override public String displayName() { return "Локальные комиксы"; }
    @Override public String websiteUrl() { return null; }
    @Override public List<String> imageDomains() { return List.of(); }

    @jakarta.annotation.PostConstruct
    void recoverImports() throws IOException {
        if (!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) return;
        try (var children=Files.list(root)) {
            for (Path path : children.toList()) {
                String name=path.getFileName().toString();
                if (name.matches("import-[0-9]+") && Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) clean(path);
                if (name.matches("archive-[0-9]+[.]zip") && Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public synchronized Imported upload(@RequestParam String title, @RequestParam String chapter,
                                        @RequestParam String language, @RequestParam("files") List<MultipartFile> files)
            throws IOException {
        validate(title,chapter,language);
        if (files.size() == 1 && archive(files.getFirst().getOriginalFilename())) {
            if (files.getFirst().getSize() > MAX_CHAPTER) throw invalid("Архив превышает 256 МиБ.");
            Files.createDirectories(root);
            Path zip = Files.createTempFile(root,"archive-",".zip");
            try {
                files.getFirst().transferTo(zip);
                return importArchive(title,chapter,language,zip);
            } finally { Files.deleteIfExists(zip); }
        }
        return save(title,chapter,language,files.stream()
                .map(file -> new Input(file.getOriginalFilename(),file.getSize(),file::getInputStream)).toList());
    }

    @PostMapping("/folder")
    public synchronized Imported folder(@RequestBody Folder request) throws IOException {
        validate(request.title(),request.chapter(),request.language());
        if (importRoot == null) throw invalid("Настройте KADR_LOCAL_IMPORT_DIR для импорта с сервера.");
        if (request.path() == null || request.path().isBlank()) throw invalid("Укажите папку главы.");
        Path directory = importRoot.resolve(request.path()).normalize();
        if (!directory.startsWith(importRoot) || !directory.toRealPath().startsWith(importRoot.toRealPath())
                || !Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) throw invalid("Недоступная папка главы.");
        for (Path p=directory; p!=null && p.startsWith(importRoot); p=p.getParent()) {
            if (Files.isSymbolicLink(p)) throw invalid("Ссылки в пути импорта не поддерживаются.");
        }
        try (var files = Files.list(directory)) {
            var selected = files.limit(MAX_FILES + 1L).toList();
            if (selected.size() > MAX_FILES || selected.stream().anyMatch(Files::isSymbolicLink)) {
                throw invalid("Слишком много файлов или обнаружена символическая ссылка.");
            }
            var images = new ArrayList<Input>();
            for (Path file : selected) if (isImageName(file.toString()) && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) {
                images.add(new Input(file.getFileName().toString(),Files.size(file),() -> Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)));
            }
            return save(request.title(),request.chapter(),request.language(),images);
        }
    }

    private Imported importArchive(String title,String chapter,String language,Path archive) throws IOException {
        try (var zip = new ZipFile(archive.toFile())) {
            if (zip.size() > MAX_FILES) throw invalid("В архиве слишком много файлов.");
            var inputs = new ArrayList<Input>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                checkName(entry.getName());
                if (entry.isDirectory() || !isImageName(entry.getName())) continue;
                long length = entry.getSize();
                if (length < 0 || length > MAX_PAGE || length > 0 && (entry.getCompressedSize() <= 0
                        || length > Math.max(MAX_PAGE / 20L,entry.getCompressedSize()*100L))) {
                    throw invalid("Слишком большой файл или степень сжатия.");
                }
                inputs.add(new Input(entry.getName(),length,() -> zip.getInputStream(entry)));
            }
            return save(title,chapter,language,inputs);
        } catch (java.util.zip.ZipException error) { throw invalid("Не удалось открыть CBZ или ZIP."); }
    }

    private Imported save(String title,String chapter,String language,List<Input> inputs) throws IOException {
        if (inputs.isEmpty() || inputs.size() > MAX_FILES) throw invalid("Ожидается от 1 до 1000 изображений.");
        var names = new HashSet<String>();
        for (Input input : inputs) {
            checkName(input.name());
            if (!names.add(input.name().toLowerCase(Locale.ROOT)) || input.length() > MAX_PAGE) {
                throw invalid("Повтор имени или изображение превышает 20 МиБ.");
            }
        }
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root,"import-");
        try {
            var digest = digest();
            digest.update(language.getBytes(StandardCharsets.UTF_8));
            long size = 0;
            var media = new ArrayList<String>();
            for (Input input : inputs.stream().sorted(Comparator.comparing(Input::name,LocalComics::natural)).toList()) {
                byte[] bytes;
                try (InputStream stream = input.open().get()) { bytes = stream.readNBytes(MAX_PAGE+1); }
                size += bytes.length;
                if (bytes.length > MAX_PAGE || size > MAX_CHAPTER) throw invalid("Лимит размера главы превышен.");
                media.add(imageType(bytes));
                digest.update(digest().digest(bytes));
                Files.write(staging.resolve(Integer.toString(media.size()-1)),bytes,StandardOpenOption.CREATE_NEW);
            }
            String normalized = java.text.Normalizer.normalize(title.strip(),java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
            String titleKey = HexFormat.of().formatHex(digest().digest(normalized.getBytes(StandardCharsets.UTF_8)));
            String chapterKey = HexFormat.of().formatHex(digest.digest());
            var comic = comic(titleKey,title.strip());
            titles.sync(List.of(comic));
            UUID internal = jdbc.sql("SELECT id FROM titles WHERE source_id='local' AND external_title_id=:id")
                    .param("id",titleKey).query(UUID.class).single();
            Path target = root.resolve(titleKey + "-" + chapterKey);
            boolean installed = !Files.exists(target);
            if (installed) Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);
            int count;
            try {
                count = jdbc.sql("""
                        INSERT INTO local_comic_chapters(title_id,chapter_id,language,chapter_title,media_types)
                        VALUES(:title,:chapter,:language,:label,CAST(:media AS text[])) ON CONFLICT DO NOTHING
                        """).param("title",internal).param("chapter",chapterKey).param("language",language)
                        .param("label",chapter.strip()).param("media","{" + String.join(",",media) + "}").update();
            } catch (RuntimeException error) {
                if (installed) clean(target);
                throw error;
            }
            jdbc.sql("INSERT INTO connected_sources(source_id) VALUES('local') ON CONFLICT DO NOTHING").update();
            queue.getObject().enqueue("local",titleKey);
            return new Imported("local",titleKey,chapterKey,language,media.size(),count==0);
        } finally { clean(staging); }
    }

    @Override public List<SourceTitle> searchTitles(String query) {
        return jdbc.sql("SELECT external_title_id,title FROM titles WHERE source_id='local' ORDER BY title,id")
                .query((row,n) -> comic(row.getString(1),row.getString(2))).list().stream()
                .filter(t -> t.title().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
    }

    @Override public SourceTitle getTitle(String external) {
        return titles.find(id(),external).orElseThrow(() -> missing("TITLE_NOT_FOUND"));
    }

    @Override public List<SourceChapter> listChapters(String external) {
        return chapters(external).stream().map(c -> new SourceChapter(c.id(),null,c.label(),List.of(c.language()),
                c.media().size(),c.id(),null,c.imported())).toList();
    }

    private List<LocalChapter> chapters(String external) {
        return jdbc.sql("""
                SELECT c.* FROM local_comic_chapters c JOIN titles t ON t.id=c.title_id
                WHERE t.source_id='local' AND t.external_title_id=:id ORDER BY c.imported_at,c.chapter_id
                """).param("id",external).query((row,n) -> new LocalChapter(row.getString("chapter_id"),
                        row.getString("language"),row.getString("chapter_title"),
                        List.of((String[])row.getArray("media_types").getArray()),row.getTimestamp("imported_at").toInstant())).list();
    }

    @Override public ChapterPages getPages(String title,String chapter,String language) {
        var editions = chapters(title).stream().filter(c -> c.language().equals(language)).toList();
        for (int i=0;i<editions.size();i++) {
            LocalChapter current=editions.get(i);
            if (!current.id().equals(chapter)) continue;
            var pages = java.util.stream.IntStream.range(0,current.media().size())
                    .mapToObj(index -> new Page(index,"/local-comics/" + chapter + "/" + index)).toList();
            return new ChapterPages(chapter,current.label(),language,pages.size(),
                    i==0?null:editions.get(i-1).id(),i+1==editions.size()?null:editions.get(i+1).id(),
                    new Attribution(null,"Локальная копия","/catalog/local/" + title,null),pages);
        }
        throw missing("CHAPTER_NOT_FOUND");
    }

    @Override public PublicSourceClient.Image serverImage(String title,String chapter,String language,int index) {
        var edition = chapters(title).stream().filter(c -> c.id().equals(chapter) && c.language().equals(language))
                .findFirst().orElseThrow(() -> missing("CHAPTER_NOT_FOUND"));
        if (index < 0 || index >= edition.media().size()) throw missing("PAGE_NOT_FOUND");
        try {
            Path file = root.resolve(title + "-" + chapter).resolve(Integer.toString(index));
            try (var stream = Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
                byte[] data = stream.readNBytes(MAX_PAGE+1);
                if (data.length > MAX_PAGE || !imageType(data).equals(edition.media().get(index))) throw missing("PAGE_NOT_FOUND");
                return new PublicSourceClient.Image(MediaType.valueOf(edition.media().get(index)),data);
            }
        } catch (IOException e) { throw missing("PAGE_NOT_FOUND"); }
    }

    private SourceTitle comic(String id,String title) {
        return new SourceTitle(id(),id,displayName(),title,null,null,null,"/catalog/local/" + id,null,null,null,null,null);
    }

    private static String imageType(byte[] data) throws IOException {
        try (var stream = new javax.imageio.stream.MemoryCacheImageInputStream(new java.io.ByteArrayInputStream(data))) {
            var decoders=ImageIO.getImageReaders(stream);
            if (!decoders.hasNext()) throw invalid("Поддерживаются JPEG, PNG и GIF.");
            var reader=decoders.next();
            try {
                reader.setInput(stream,true,true);
                if ((long)reader.getWidth(0)*reader.getHeight(0)>40_000_000) throw invalid("Изображение слишком большое.");
                return switch(reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg","jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "gif" -> "image/gif";
                    default -> throw invalid("Поддерживаются JPEG, PNG и GIF.");
                };
            } finally { reader.dispose(); }
        }
    }

    private static void checkName(String value) {
        if (value == null || value.length()>500 || value.indexOf('\0')>=0 || value.indexOf(':')>=0
                || value.indexOf('\\')>=0 || value.startsWith("/") || Arrays.asList(value.split("/")).contains("..")) {
            throw invalid("Недопустимый путь файла.");
        }
    }
    private static void validate(String title,String chapter,String language) {
        if (title==null || title.isBlank() || title.length()>200 || chapter==null || chapter.isBlank() || chapter.length()>200
                || title.contains("\0") || chapter.contains("\0") || !List.of("ru","en").contains(language)) {
            throw invalid("Укажите тайтл, главу и язык RU или EN.");
        }
    }
    private static int natural(String a,String b) {
        var pattern=java.util.regex.Pattern.compile("[0-9]+|[^0-9]+");
        var x=pattern.matcher(a); var y=pattern.matcher(b);
        while (x.find() && y.find()) {
            String left=x.group(); String right=y.group();
            int result=Character.isDigit(left.charAt(0)) && Character.isDigit(right.charAt(0))
                    ? new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right)) : left.compareToIgnoreCase(right);
            if (result!=0) return result;
        }
        int length=Integer.compare(a.length(),b.length());
        return length==0?a.compareTo(b):length;
    }
    private static boolean archive(String name) { return name!=null && name.toLowerCase(Locale.ROOT).matches(".*\\.(zip|cbz)$"); }
    private static boolean isImageName(String name) { return name.toLowerCase(Locale.ROOT).matches(".*\\.(png|gif|jpe?g)$"); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void clean(Path folder) throws IOException {
        if (!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)) return;
        try (var files=Files.list(folder)) { for (Path file : files.toList()) Files.deleteIfExists(file); }
        Files.deleteIfExists(folder);
    }
    private static ApiException invalid(String message) { return ApiException.problem(HttpStatus.UNPROCESSABLE_CONTENT,"LOCAL_IMPORT_INVALID",message); }
    private static ApiException missing(String code) { return ApiException.problem(HttpStatus.NOT_FOUND,code,"Локальный файл не найден."); }
    @FunctionalInterface interface Open { InputStream get() throws IOException; }
    record Input(String name,long length,Open open) {}
    record LocalChapter(String id,String language,String label,List<String> media,Instant imported) {}
    record Folder(String title,String chapter,String language,String path) {}
    record Imported(String sourceId,String externalTitleId,String chapterId,String language,int pageCount,boolean duplicate) {}
}
