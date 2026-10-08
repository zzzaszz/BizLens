package zzz.service.forum.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OpenAiCompatibleEmbeddingProviderTest {

    @Test
    public void requestFactoryUsesConfiguredTimeouts() {
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider();

        SimpleClientHttpRequestFactory factory = provider.requestFactory(1234, 5678);

        assertEquals(1234, ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(5678, ReflectionTestUtils.getField(factory, "readTimeout"));
    }

    @Test
    public void requestFactoryFallsBackWhenTimeoutsInvalid() {
        OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider();

        SimpleClientHttpRequestFactory factory = provider.requestFactory(0, -1);

        assertEquals(5000, ReflectionTestUtils.getField(factory, "connectTimeout"));
        assertEquals(20000, ReflectionTestUtils.getField(factory, "readTimeout"));
    }
}
