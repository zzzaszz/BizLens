package zzz.service.forum.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AiProviderTest {

    @Test
    public void defaultListModelsParsesOpenAiCompatibleResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> auth = new AtomicReference<>();
        server.createContext("/models", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = ("{\"data\":[{\"id\":\"model-b\"},{\"id\":\"model-a\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            AiProvider provider = request -> AiResponse.of("");
            List<String> models = provider.listModels("http://127.0.0.1:" + server.getAddress().getPort(), "test-key");

            assertEquals("Bearer test-key", auth.get());
            assertEquals(java.util.Arrays.asList("model-a", "model-b"), models);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void defaultListModelsReturnsEmptyWhenPreconditionsMissing() {
        AiProvider provider = request -> AiResponse.of("");

        assertEquals(Collections.emptyList(), provider.listModels("", "test-key"));
        assertEquals(Collections.emptyList(), provider.listModels("http://example.com", ""));
    }
}
