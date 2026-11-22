package io.github.mangareader.source.mangadex;

import io.github.mangareader.error.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

@Component
class MangaDexClient {
    static final String WEBSITE = "https://mangadex.org";
    private static final String AGENT = "KadrCourseProject/0.1";
    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final Set<String> IMAGE_TYPES = Set.of("jpeg", "png", "gif", "webp", "avif");
    private static final Logger LOG = LoggerFactory.getLogger(MangaDexClient.class);

    private final RestClient api;
    private final RestClient images;
    private long nextRequest;
    private long blockedUntil;

    MangaDexClient() {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        var uris = new DefaultUriBuilderFactory("https://api.mangadex.org");
        api = RestClient.builder().requestFactory(factory).uriBuilderFactory(uris)
                .defaultHeader(HttpHeaders.USER_AGENT, AGENT).build();
        images = RestClient.builder().requestFactory(factory).defaultHeader(HttpHeaders.USER_AGENT, AGENT).build();
    }

    JsonNode get(Function<UriBuilder, URI> uri) {
        acquireRequestSlot();
        try {
            JsonNode result = api.get().uri(uri).retrieve().body(JsonNode.class);
            if (result == null || !"ok".equals(result.path("result").asString())) {
                throw bad("Unexpected MangaDex response");
            }
            return result;
        } catch (ResourceAccessException exception) {
            throw ApiException.sourceUnavailable(exception);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 404) {
                return null;
            }
            if (status == 429) {
                backOff(exception.getResponseHeaders());
                throw ApiException.withDetails(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "SOURCE_RATE_LIMITED", "Источник ограничил частоту запросов.",
                        java.util.Map.of("stage", "CATALOG", "retryable", true,
                                "retryAfterSeconds", Math.max(1, TimeUnit.NANOSECONDS.toSeconds(blockedUntil-System.nanoTime()))));
            }
            if (status == 401 || status == 403) {
                throw ApiException.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "SOURCE_ACCESS_DENIED", "Источник ограничил доступ к этим данным.");
            }
            if (status >= 500) {
                throw ApiException.sourceUnavailable(exception);
            }
            throw ApiException.sourceBadResponse(exception);
        } catch (RestClientException exception) {
            throw ApiException.sourceBadResponse(exception);
        }
    }

    Image image(URI uri) {
        long start = System.nanoTime();
        Image image = null;
        boolean cached = false;
        try {
            var response = images.get().uri(uri).exchange((request, received) -> {
                int status = received.getStatusCode().value();
                if (status != 200) {
                    return null;
                }
                MediaType type = received.getHeaders().getContentType();
                if (type == null || !type.getType().equals("image")
                        || !IMAGE_TYPES.contains(type.getSubtype())
                        || received.getHeaders().getContentLength() > MAX_IMAGE_BYTES) {
                    return null;
                }
                byte[] bytes = received.getBody().readNBytes(MAX_IMAGE_BYTES + 1);
                if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                    return null;
                }
                String cacheHeader = received.getHeaders().getFirst("X-Cache");
                return new ImageDownload(new Image(type, bytes), cacheHeader != null && cacheHeader.startsWith("HIT"));
            });
            if (response != null) {
                image = response.image();
                cached = response.cached();
            }
            return image;
        } catch (RestClientException exception) {
            LOG.debug("MangaDex image could not be loaded", exception);
            return null;
        } finally {
            if (uri.getHost().endsWith(".mangadex.network")) {
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                report(new ImageReport(uri.toString(), image != null, cached,
                        image == null ? 0 : image.bytes().length, duration));
            }
        }
    }

    private void report(ImageReport report) {
        try {
            images.post().uri("https://api.mangadex.network/report").contentType(MediaType.APPLICATION_JSON)
                    .body(report).retrieve().toBodilessEntity();
        } catch (RestClientException exception) {
            LOG.debug("MangaDex image report failed", exception);
        }
    }

    private synchronized void acquireRequestSlot() {
        long now = System.nanoTime();
        if (blockedUntil > now) {
            throw ApiException.withDetails(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "SOURCE_RATE_LIMITED", "Источник ограничил частоту запросов.",
                    java.util.Map.of("retryable", true, "retryAfterSeconds",
                            Math.max(1,TimeUnit.NANOSECONDS.toSeconds(blockedUntil-now))));
        }
        try {
            TimeUnit.NANOSECONDS.sleep(Math.max(0, nextRequest - now));
            nextRequest = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(250);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.sourceUnavailable(exception);
        }
    }

    private synchronized void backOff(HttpHeaders headers) {
        long seconds = 10;
        if (headers != null) {
            try {
                String reset = headers.getFirst("X-RateLimit-Retry-After");
                seconds = reset == null ? Long.parseLong(headers.getFirst(HttpHeaders.RETRY_AFTER))
                        : Long.parseLong(reset) - Instant.now().getEpochSecond();
            } catch (NumberFormatException ignored) {
                // Use the default pause when the server sends no numeric interval.
            }
        }
        blockedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.clamp(seconds, 1, 3600));
    }

    static ApiException bad(String message) {
        return ApiException.sourceBadResponse(new IllegalStateException(message));
    }

    record Image(MediaType type, byte[] bytes) {}
    private record ImageDownload(Image image, boolean cached) {}
    private record ImageReport(String url, boolean success, boolean cached, int bytes, long duration) {}
}
