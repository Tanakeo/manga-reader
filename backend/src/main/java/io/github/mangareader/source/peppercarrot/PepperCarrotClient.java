package io.github.mangareader.source.peppercarrot;

import io.github.mangareader.error.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
class PepperCarrotClient {
    static final String ORIGIN = "https://www.peppercarrot.com";

    private final RestClient client;

    PepperCarrotClient() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().baseUrl(ORIGIN).requestFactory(factory)
                .defaultHeader("User-Agent", "MangaReaderCourseProject/0.1")
                .build();
    }

    String get(String path) {
        try {
            String body = client.get().uri(path).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw ApiException.sourceBadResponse(new IllegalStateException("Empty response for " + path));
            }
            return body;
        } catch (ResourceAccessException exception) {
            throw ApiException.sourceUnavailable(exception);
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 429 || status >= 500) {
                throw ApiException.sourceUnavailable(exception);
            }
            throw ApiException.sourceBadResponse(exception);
        } catch (RestClientException exception) {
            throw ApiException.sourceBadResponse(exception);
        }
    }
}
