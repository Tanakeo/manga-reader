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
    private final RestClient http;
    private final JsonMapper json;

    public PublicSourceClient(JsonMapper json) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().requestFactory(factory)
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
            var data = parse(script.data());
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
            var request = http.get().uri(uri).accept(accept);
            if (siteId != null) request.header("Site-Id", siteId);
            String body = request.retrieve().body(String.class);
            if (body == null || body.isBlank()) throw badResponse("Empty response");
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
}
