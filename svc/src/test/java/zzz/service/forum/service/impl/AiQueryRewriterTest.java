package zzz.service.forum.service.impl;

import org.junit.jupiter.api.Test;
import zzz.service.forum.ai.AiProvider;
import zzz.service.forum.ai.AiRequest;
import zzz.service.forum.ai.AiResponse;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AiQueryRewriterTest {

    @Test
    public void cachesModelRewriteResultForSameProviderModelAndQuestion() throws Exception {
        AiQueryRewriter rewriter = new AiQueryRewriter();
        AtomicInteger calls = new AtomicInteger();
        setField(rewriter, "aiProvider", new AiProvider() {
            @Override
            public AiResponse chat(AiRequest request) {
                calls.incrementAndGet();
                return AiResponse.of("order,timeout,OrderService");
            }
        });

        List<String> first = rewriter.rewrite("订单超时怎么处理", "m1", "key", "http://model", "custom");
        List<String> second = rewriter.rewrite(" 订单超时怎么处理 ", "m1", "key", "http://model", "custom");

        assertEquals(java.util.Arrays.asList("order", "timeout", "OrderService"), first);
        assertEquals(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    public void fallbackResultIsCachedWhenModelFails() throws Exception {
        AiQueryRewriter rewriter = new AiQueryRewriter();
        AtomicInteger calls = new AtomicInteger();
        setField(rewriter, "aiProvider", new AiProvider() {
            @Override
            public AiResponse chat(AiRequest request) {
                calls.incrementAndGet();
                throw new RuntimeException("down");
            }
        });

        List<String> first = rewriter.rewrite("OrderTimeoutHandler 怎么触发", "m1", "key", "http://model", "custom");
        List<String> second = rewriter.rewrite("OrderTimeoutHandler 怎么触发", "m1", "key", "http://model", "custom");

        assertEquals(java.util.Collections.singletonList("OrderTimeoutHandler"), first);
        assertEquals(first, second);
        assertEquals(1, calls.get());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
