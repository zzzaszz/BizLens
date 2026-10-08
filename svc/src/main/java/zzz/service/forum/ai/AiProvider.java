package zzz.service.forum.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 大模型调用抽象层。
 * <p>
 * 面向业务语义（会话 + 消息），不暴露具体厂商细节。
 * 换模型只需新增一个 Provider 实现，业务代码（AiService）无需改动。
 */
public interface AiProvider {

    /**
     * 一次性返回模型回复（OpenAI 兼容格式）。
     *
     * @param request 请求体（模型 / messages / 参数 / 覆盖的 key）
     * @return 模型回复
     */
    AiResponse chat(AiRequest request);

    /**
     * 流式返回模型回复（OpenAI 兼容 SSE 格式）。
     * <p>
     * 默认实现退化为一次性返回（一次 onToken 交付完整回复），
     * 支持流式的 Provider 应重写为逐段增量回调。
     *
     * @param request  请求体
     * @param listener 增量回调
     */
    default void chatStream(AiRequest request, AiStreamListener listener) {
        try {
            AiResponse resp = chat(request);
            if (resp.getContent() != null && !resp.getContent().isEmpty()) {
                listener.onToken(resp.getContent());
            }
            listener.onDone();
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    /**
     * 厂商标识：deepseek / openai / qwen。
     * 用于角色自带 Key 时选择对应 Provider。
     */
    default String providerName() {
        return "default";
    }

    /**
     * 拉取厂商可用模型列表（OpenAI 兼容的 GET {baseUrl}/models）。
     * <p>
     * 仅用于填充配置表单的下拉选项，属于辅助能力；通用 Provider 默认尝试
     * OpenAI-compatible /models，调用失败时返回空列表，由调用方回退到预置列表，不能因此阻断表单。
     *
     * @param baseUrl 厂商 base-url
     * @param apiKey  调用方提供的 Key
     * @return 模型 id 列表；不支持或调用失败时返回空列表
     */
    default List<String> listModels(String baseUrl, String apiKey) {
        Logger log = LoggerFactory.getLogger(AiProvider.class);
        if (baseUrl == null || baseUrl.trim().isEmpty() || apiKey == null || apiKey.trim().isEmpty()) {
            log.info("跳过模型列表调用：provider={}, baseUrl={}, apiKeyPresent={}",
                    getClass().getSimpleName(), baseUrl, apiKey != null && !apiKey.trim().isEmpty());
            return Collections.emptyList();
        }
        String urlText = trimTrailingSlash(baseUrl) + "/models";
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                log.warn("模型列表接口返回非 2xx：provider={}, url={}, status={}",
                        getClass().getSimpleName(), urlText, status);
                return Collections.emptyList();
            }
            String body;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
                body = builder.toString();
            }
            JSONObject object = JSON.parseObject(body);
            JSONArray data = object == null ? null : object.getJSONArray("data");
            if (data == null || data.isEmpty()) {
                return Collections.emptyList();
            }
            List<String> models = new ArrayList<>();
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = data.getJSONObject(i);
                String id = item == null ? null : item.getString("id");
                if (id != null && !id.trim().isEmpty()) {
                    models.add(id.trim());
                }
            }
            Collections.sort(models);
            return models;
        } catch (Exception e) {
            log.warn("模型列表接口调用失败：provider={}, url={}, err={}",
                    getClass().getSimpleName(), urlText, e.getMessage());
            return Collections.emptyList();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    static String trimTrailingSlash(String value) {
        String v = value.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }
}
