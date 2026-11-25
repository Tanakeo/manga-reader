package io.github.mangareader.source;

import io.github.mangareader.error.ApiException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PublicSourceClient {
    private static final java.util.Set<String> IMAGE_TYPES = java.util.Set.of("jpeg", "png", "gif", "webp", "avif");
    private final RestClient http;
    private final RestClient imageHttp;
    private final JsonMapper json;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> metadataSlots = new java.util.concurrent.ConcurrentHashMap<>();

    public PublicSourceClient(JsonMapper json) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory)
                .defaultHeader("User-Agent", "KadrCourseProject/0.1").build();
        var imageFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4)).build());
        imageFactory.setReadTimeout(Duration.ofSeconds(15));
        this.imageHttp = RestClient.builder().requestFactory(imageFactory)
                .defaultHeader("User-Agent", "KadrCourseProject/0.1").build();
        this.json = json;
    }

    public Document html(URI uri, Supplier<ApiException> missing) {
        return Jsoup.parse(get(uri, MediaType.TEXT_HTML, null, missing), uri.toString());
    }

    public JsonNode json(URI uri, String siteId, Supplier<ApiException> missing) {
        return parse(get(uri, MediaType.APPLICATION_JSON, siteId, missing));
    }

    public JsonNode parse(String value) {
        try {
            return json.readTree(value);
        } catch (JacksonException exception) {
            throw ApiException.sourceBadResponse(exception);
        }
    }

    public JsonNode structuredData(Document document, String type) {
        for (var script : document.select("script[type=application/ld+json]")) {
            JsonNode data;
            try { data = parse(script.data()); }
            catch (ApiException exception) { continue; }
            if (type.equals(data.path("@type").asText())) return data;
        }
        throw badResponse("Missing structured data: " + type);
    }

    public static UriComponentsBuilder url(String origin, String... segments) {
        return UriComponentsBuilder.fromUriString(origin).pathSegment(segments);
    }

    public static String text(JsonNode value) {
        return value.isValueNode() && !value.isNull() && !value.asText().isBlank()
                ? value.asText().strip() : null;
    }

    public static java.time.Instant instant(String value) {
        if (value == null) return null;
        try { return java.time.Instant.parse(value); }
        catch (java.time.format.DateTimeParseException exception) { return null; }
    }

    public static URI httpsUrl(String value, String... domains) {
        if (value == null) return null;
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            return Arrays.stream(domains).anyMatch(domain -> host.equalsIgnoreCase(domain)
                    || host.toLowerCase(Locale.ROOT).endsWith("." + domain)) ? uri : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public static ApiException badResponse(String detail) {
        return ApiException.sourceBadResponse(new IllegalStateException(detail));
    }

    private String get(URI uri, MediaType accept, String siteId, Supplier<ApiException> missing) {
        try {
            Wire response = fetch(uri, accept, siteId, null);
            if (response.status() == 404 && missing != null) throw missing.get();
            if (response.status() < 200 || response.status() >= 300) throw failure(response, false);
            if (MediaType.APPLICATION_JSON.equals(accept) && (response.type() == null
                    || !response.type().getSubtype().contains("json"))) throw badResponse("Expected JSON Content-Type");
            String body = new String(response.bytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (body.isBlank()) throw badResponse("Empty response");
            return body;
        } catch (ResourceAccessException exception) {
            throw ApiException.sourceUnavailable(exception);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 404 && missing != null) throw missing.get();
            if (status == 401 || status == 403) {
                throw ApiException.problem(HttpStatus.SERVICE_UNAVAILABLE,
                        "SOURCE_ACCESS_DENIED", "Источник ограничил доступ к этим данным.");
            }
            if (status == 429 || status >= 500) throw ApiException.sourceUnavailable(exception);
            throw ApiException.sourceBadResponse(exception);
        } catch (RestClientException exception) {
            throw ApiException.sourceBadResponse(exception);
        }
    }

    public Image image(URI uri, String referer) {
        try {
            Wire response = fetch(uri, MediaType.valueOf("image/*"), null, referer);
            if (response.status() != 200) throw failure(response, true);
            MediaType type = response.type();
            byte[] bytes = response.bytes();
            if (!matchesImage(type,bytes)) {
                throw ApiException.withDetails(HttpStatus.BAD_GATEWAY, "IMAGE_BAD_RESPONSE",
                        "Источник вернул некорректное изображение.", java.util.Map.of("stage", "IMAGE", "retryable", false));
            }
            return new Image(type, bytes);
        } catch (ResourceAccessException exception) {
            throw ApiException.withDetails(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_UNAVAILABLE",
                    "Не удалось загрузить изображение.", java.util.Map.of("stage", "IMAGE", "retryable", true));
        }
    }

    public static boolean matchesImage(MediaType type,byte[] bytes) {
    boolean signature = bytes.length >= 12 && type != null && switch (type.getSubtype()) {
        case "jpeg" -> (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
        case "png" -> (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10;
        case "gif" -> bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8';
        case "webp" -> bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        case "avif" -> bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p'
                && bytes[8] == 'a' && bytes[9] == 'v' && bytes[10] == 'i' && (bytes[11] == 'f' || bytes[11] == 's');
        default -> false;
    };
        return signature && "image".equals(type.getType()) && IMAGE_TYPES.contains(type.getSubtype());
    }

    private Wire fetch(URI initial, MediaType accept, String siteId, String referer) {
        URI uri = initial;
        for (int redirects = 0; redirects <= 3; redirects++) {
            if (!"image".equals(accept.getType())) acquireMetadataSlot(uri.getHost());
            var request = ("image".equals(accept.getType()) ? imageHttp : http).get().uri(uri).accept(accept);
            if (siteId != null) request.header("Site-Id", siteId);
            if (referer != null) request.header("Referer", referer);
            Wire response = request.exchange((sent, received) -> {
                long declared = received.getHeaders().getContentLength();
                if (declared > 20 * 1024 * 1024) throw badResponse("Source response is too large");
                byte[] bytes = received.getBody().readNBytes(20 * 1024 * 1024 + 1);
                if (bytes.length > 20 * 1024 * 1024) throw badResponse("Source response is too large");
                return new Wire(received.getStatusCode().value(), received.getHeaders().getContentType(), bytes,
                        received.getHeaders().getLocation(), received.getHeaders().getFirst("Retry-After"));
            });
            if (response.status() < 300 || response.status() >= 400) return response;
            URI target = response.location() == null ? null : uri.resolve(response.location());
            if (target == null || !trustedRedirect(initial, target)) throw badResponse("Unexpected source redirect");
            uri = target;
        }
        throw badResponse("Too many source redirects");
    }

    private boolean trustedRedirect(URI original, URI target) {
        String[] domains = original.getHost().contains("manga") && !original.getHost().contains("mangalib")
                ? new String[]{"kakamanga.com", "katamanga.com", "mangagoku.com"}
                : new String[]{original.getHost()};
        return httpsUrl(target.toString(), domains) != null;
    }

    private void acquireMetadataSlot(String host) {
        long slot = metadataSlots.compute(host, (key, previous) ->
                Math.max(System.currentTimeMillis(), previous == null ? 0 : previous) + 1000) - 1000;
        try { Thread.sleep(Math.max(0, slot-System.currentTimeMillis())); }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.sourceUnavailable(exception);
        }
    }

    private ApiException failure(Wire response, boolean image) {
        int status = response.status();
        String code = image ? "IMAGE_UNAVAILABLE" : "SOURCE_UNAVAILABLE";
        if (status == 401 || status == 403) code = image ? "IMAGE_ACCESS_DENIED" : "SOURCE_ACCESS_DENIED";
        if (status == 429) code = image ? "IMAGE_UNAVAILABLE" : "SOURCE_RATE_LIMITED";
        int retry = 0;
        if (response.retryAfter() != null) {
            try { retry = Integer.parseInt(response.retryAfter()); }
            catch (NumberFormatException exception) {
                try { retry = (int) java.time.Duration.between(java.time.Instant.now(),
                        java.time.ZonedDateTime.parse(response.retryAfter(),
                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds(); }
                catch (java.time.DateTimeException ignored) { retry = 10; }
            }
        }
        return ApiException.withDetails(HttpStatus.SERVICE_UNAVAILABLE, code,
                status == 403 ? "Источник ограничил доступ к этим данным." : "Источник временно недоступен.",
                java.util.Map.of("stage", image ? "IMAGE" : "CATALOG", "upstreamStatus", status,
                        "retryable", status == 429 || status >= 500, "retryAfterSeconds", Math.clamp(retry, 0, 3600)));
    }

    public record Image(MediaType type, byte[] bytes) {}
    private record Wire(int status, MediaType type, byte[] bytes, URI location, String retryAfter) {}
}
