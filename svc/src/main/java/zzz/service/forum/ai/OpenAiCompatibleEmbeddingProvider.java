package zzz.service.forum.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible embeddings provider: POST {base-url}/embeddings. */
@Service
public class OpenAiCompatibleEmbeddingProvider implements AiEmbeddingProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingProvider.class);

    @Value("${ai.embedding.enabled:false}")
    private boolean enabled;
    @Value("${ai.embedding.base-url:}")
    private String baseUrl;
    @Value("${ai.embedding.api-key:}")
    private String apiKey;
    @Value("${ai.embedding.model:text-embedding-3-small}")
    private String model;
    @Value("${ai.embedding.dimension:1536}")
    private int dimension;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public boolean isEnabled() {
        return enabled && !isBlank(baseUrl) && !isBlank(apiKey) && !isBlank(model) && dimension > 0;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public List<Double> embed(String text) {
        if (!isEnabled() || isBlank(text)) {
            return java.util.Collections.emptyList();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", text);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        String url = trimTrailingSlash(baseUrl) + "/embeddings";
        ResponseEntity<Map> response = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), Map.class);
        Map<?, ?> responseBody = response.getBody();
        if (responseBody == null) {
            return java.util.Collections.emptyList();
        }
        Object dataValue = responseBody.get("data");
        if (!(dataValue instanceof List) || ((List<?>) dataValue).isEmpty()) {
            return java.util.Collections.emptyList();
        }
        Object first = ((List<?>) dataValue).get(0);
        if (!(first instanceof Map)) {
            return java.util.Collections.emptyList();
        }
        Object embeddingValue = ((Map<?, ?>) first).get("embedding");
        if (!(embeddingValue instanceof List)) {
            return java.util.Collections.emptyList();
        }
        List<Double> vector = EmbeddingVectorUtils.parseNumbers((List<?>) embeddingValue);
        if (vector.size() != dimension) {
            log.warn("embedding 维度不匹配，丢弃本次结果: expected={}, actual={}", dimension, vector.size());
            return java.util.Collections.emptyList();
        }
        return vector;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimTrailingSlash(String value) {
        String v = value.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }
}
