package io.github.mangareader.source;

import com.sun.net.httpserver.HttpServer;
import io.github.mangareader.error.ApiException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class PublicSourceClientTest {
    private HttpServer server;
    private URI uri;
    private final PublicSourceClient client = new PublicSourceClient(JsonMapper.builder().build());

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/image");
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void rejectsHtmlDisguisedAsJpeg() {
        respond(200, "image/jpeg", "<html>Access denied</html>".getBytes(StandardCharsets.UTF_8));
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> client.image(uri, null))
                .matches(error -> "IMAGE_BAD_RESPONSE".equals(error.code()));
    }
    @Test void rejectsSignatureForAnotherMimeType() {
        respond(200, "image/png", new byte[]{(byte)255,(byte)216,(byte)255,0,0,0,0,0,0,0,0,0});
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> client.image(uri, null))
                .matches(error -> "IMAGE_BAD_RESPONSE".equals(error.code()));
    }
    @Test void distinguishesAccessDenialFromAnEmptyManifest() {
        respond(403, "text/html", new byte[0]);
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> client.image(uri, null))
                .matches(error -> "IMAGE_ACCESS_DENIED".equals(error.code()));
    }
    @Test void refusesRedirectToAnotherHost() {
        server.createContext("/image", exchange -> {
            exchange.getResponseHeaders().set("Location", "https://untrusted.invalid/image.jpg");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> client.image(uri, null));
    }
    private void respond(int status, String type, byte[] body) {
        server.createContext("/image", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
    }
}
