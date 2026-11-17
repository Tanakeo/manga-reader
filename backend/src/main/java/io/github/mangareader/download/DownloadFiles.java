package io.github.mangareader.download;

import io.github.mangareader.error.ApiException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
class DownloadFiles {
    private static final int MAX_BYTES = 24 * 1024 * 1024;
    private final Path root;
    private final RestClient client;

    DownloadFiles(@Value("${KADR_OFFLINE_DIR:${KADR_DOWNLOAD_DIR:./data/downloads}}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        client = RestClient.builder().requestFactory(factory)
                .defaultHeader("User-Agent", "MangaReaderCourseProject/0.1").build();
    }

    PageFile fetch(UUID id, int index, String url) {
        URI uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"www.peppercarrot.com".equals(uri.getHost())
                || uri.getPort() != -1 || uri.getRawUserInfo() != null
                || !uri.getPath().startsWith("/0_sources/") || !uri.getPath().contains("/low-res/")
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw ApiException.sourceBadResponse(new IllegalArgumentException("Unexpected Pepper&Carrot image URL"));
        }
        try {
            return client.get().uri(uri).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw ApiException.sourceUnavailable(new IOException("Pepper&Carrot image request failed"));
                }
                var body = new BufferedInputStream(response.getBody());
                body.mark(6);
                byte[] signature = body.readNBytes(6);
                body.reset();
                String suffix = imageSuffix(signature);
                if (suffix == null || response.getHeaders().getContentLength() > MAX_BYTES) {
                    throw ApiException.sourceBadResponse(new IOException("Unexpected image format or size"));
                }
                Path directory = staging(id);
                Files.createDirectories(directory);
                var digest = hash();
                try (var output = new DigestOutputStream(Files.newOutputStream(directory.resolve(index + suffix)), digest)) {
                    byte[] buffer = new byte[16384];
                    int total = 0;
                    for (int count; (count = body.read(buffer)) != -1; ) {
                        total += count;
                        if (total > MAX_BYTES) {
                            throw ApiException.sourceBadResponse(new IOException("Image exceeds size limit"));
                        }
                        output.write(buffer, 0, count);
                    }
                    long expected = response.getHeaders().getContentLength();
                    if (expected != -1 && total != expected) {
                        throw ApiException.sourceBadResponse(new IOException("Incomplete image"));
                    }
                    return new PageFile(index, id + "/" + index + suffix, mime(suffix), total,
                            HexFormat.of().formatHex(digest.digest()));
                }
            });
        } catch (ResourceAccessException exception) {
            throw ApiException.sourceUnavailable(exception);
        }
    }

    void publish(UUID id) throws IOException {
        Files.move(staging(id), root.resolve(id.toString()), StandardCopyOption.ATOMIC_MOVE);
    }

    PageFile describe(UUID id, int index) throws IOException {
        for (String suffix : java.util.List.of(".jpg", ".gif")) {
            String key = id + "/" + index + suffix;
            Path file = root.resolve(key);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return new PageFile(index, key, mime(suffix), Files.size(file), digest(file));
            }
        }
        throw new IOException("Missing local page");
    }

    StoredImage image(PageFile page) throws IOException {
        Path file = root.resolve(page.storageKey()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) != page.sizeBytes() || !digest(file).equals(page.sha256())) {
            throw new IOException("Invalid local page");
        }
        return new StoredImage(new FileSystemResource(file), MediaType.parseMediaType(page.mimeType()));
    }

    void clear(UUID id) throws IOException {
        clearDirectory(staging(id));
        clearDirectory(root.resolve(id.toString()));
    }

    private Path staging(UUID id) {
        return root.resolve(id + ".part");
    }

    private void clearDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(directory);
        }
    }

    private static String mime(String suffix) {
        return ".jpg".equals(suffix) ? "image/jpeg" : "image/gif";
    }

    private static String digest(Path path) throws IOException {
        var digest = hash();
        try (var input = new java.security.DigestInputStream(Files.newInputStream(path), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest hash() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String imageSuffix(byte[] signature) {
        if (signature.length < 6) {
            return null;
        }
        if (signature[0] == (byte) 0xff && signature[1] == (byte) 0xd8 && signature[2] == (byte) 0xff) {
            return ".jpg";
        }
        return signature[0] == 'G' && signature[1] == 'I' && signature[2] == 'F' ? ".gif" : null;
    }

    record StoredImage(FileSystemResource resource, MediaType type) {}
    record PageFile(int index, String storageKey, String mimeType, long sizeBytes, String sha256) {}
}
