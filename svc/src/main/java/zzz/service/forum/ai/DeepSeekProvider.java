package zzz.service.forum.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek Provider：OpenAI 兼容格式，支持一次性返回与 SSE 流式。
 * <p>
 * base-url / model 来自配置；API Key 必须由业务层按角色传入，不使用服务级密钥兜底。
 */
@Service
public class DeepSeekProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekProvider.class);

    @Value("${ai.deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;
    @Value("${ai.deepseek.model:deepseek-flash}")
    private String model;

    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public AiResponse chat(AiRequest request) {
        // 1. 组装请求体（OpenAI 兼容格式）
        Map<String, Object> body = new HashMap<>();
        body.put("model", resolveModel(request));
        body.put("messages", request.getMessages());
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }

        // 2. 鉴权头：只使用角色自带 Key，避免新增调用方绕过角色权限边界。
        String useKey = resolveKey(request);
        String useBaseUrl = resolveBaseUrl(request);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(useKey);

        String url = useBaseUrl + "/chat/completions";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        // 3. 发起 POST，解析 choices[0].message.content
        try {
            ResponseEntity<Map> resp = restTemplate.postForEntity(url, entity, Map.class);
            Map<?, ?> respBody = resp.getBody();
            if (respBody == null) {
                return AiResponse.of("");
            }
            List<?> choices = (List<?>) respBody.get("choices");
            if (choices == null || choices.isEmpty()) {
                return AiResponse.of("");
            }
            Map<?, ?> first = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) first.get("message");
            Object content = message != null ? message.get("content") : null;
            return AiResponse.of(content != null ? content.toString() : "");
        } catch (HttpClientErrorException e) {
            // 401 = Key 失效 / 402 = 额度耗尽 / 429 = 限流
            int status = e.getRawStatusCode();
            AiResponse r = new AiResponse(e.getResponseBodyAsString());
            r.setKeyError(status == 401 || status == 402 || status == 429);
            log.warn("调用大模型失败: status={}, body={}", status, e.getResponseBodyAsString());
            return r;
        } catch (Exception e) {
            log.warn("调用大模型异常: {}", e.getMessage());
            return AiResponse.of("");
        }
    }

    @Override
    public void chatStream(AiRequest request, AiStreamListener listener) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", resolveModel(request));
        body.put("messages", request.getMessages());
        body.put("stream", true);
        if (request.getTemperature() != null) {
            body.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            body.put("max_tokens", request.getMaxTokens());
        }

        String useKey = resolveKey(request);
        String useBaseUrl = resolveBaseUrl(request);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(useBaseUrl + "/chat/completions");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(120000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + useKey);
            conn.getOutputStream().write(JSON.toJSONString(body).getBytes(StandardCharsets.UTF_8));

            // 逐行解析 SSE：data: {...}  /  data: [DONE]
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    try {
                        JSONObject obj = JSON.parseObject(data);
                        JSONArray choices = obj != null ? obj.getJSONArray("choices") : null;
                        if (choices == null || choices.isEmpty()) {
                            continue;
                        }
                        JSONObject delta = choices.getJSONObject(0).getJSONObject("delta");
                        String content = delta != null ? delta.getString("content") : null;
                        if (content != null && !content.isEmpty()) {
                            listener.onToken(content);
                        }
                    } catch (Exception ignore) {
                        // 单个增量解析失败不影响整体流
                    }
                }
            }
            listener.onDone();
        } catch (Exception e) {
            log.warn("流式调用大模型失败: {}", e.getMessage());
            listener.onError(e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Override
    public String providerName() {
        return "deepseek";
    }

    /**
     * 拉取可用模型：GET {baseUrl}/models，解析 OpenAI 兼容响应里的 data[].id。
     * 任何异常都吞掉返回空列表——这只是给下拉框取选项，不该阻断配置表单。
     */
    @Override
    public List<String> listModels(String baseUrl, String apiKey) {
        // 不满足前提就不发请求，但要留痕，否则排查时只看到"下拉是预置的"却不知为何
        if (isBlank(baseUrl) || isBlank(apiKey)) {
            log.info("未转发厂商模型列表接口：baseUrl={}, apiKeyPresent={}", baseUrl, !isBlank(apiKey));
            return Collections.emptyList();
        }

        String url = trimTrailingSlash(baseUrl) + "/models";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(apiKey);

            // 日志只输出 URL / 响应体 / 取用结果，绝不打印 apiKey
            log.info("转发厂商模型列表接口：GET {}", url);
            ResponseEntity<Map> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<?, ?> respBody = resp.getBody();
            log.info("厂商模型列表接口返回：status={}, body={}", resp.getStatusCodeValue(), respBody);

            if (respBody == null) {
                log.warn("返回体为空，取用数据：[]");
                return Collections.emptyList();
            }
            List<?> data = (List<?>) respBody.get("data");
            if (data == null || data.isEmpty()) {
                log.warn("返回体中没有 data 字段，取用数据：[]");
                return Collections.emptyList();
            }
            List<String> models = new ArrayList<>();
            for (Object item : data) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Object id = ((Map<?, ?>) item).get("id");
                if (id != null && !id.toString().isEmpty()) {
                    models.add(id.toString());
                }
            }
            Collections.sort(models);
            log.info("取用数据（data[].id）：{}", models);
            return models;
        } catch (Exception e) {
            log.warn("调用厂商模型列表接口失败：url={}, err={}", url, e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimTrailingSlash(String value) {
        String v = value.trim();
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }

    private String resolveModel(AiRequest request) {
        return (request.getModel() != null && !request.getModel().isEmpty()) ? request.getModel() : model;
    }

    private String resolveKey(AiRequest request) {
        return request.getApiKey();
    }

    private String resolveBaseUrl(AiRequest request) {
        return (request.getBaseUrl() != null && !request.getBaseUrl().isEmpty()) ? request.getBaseUrl() : baseUrl;
    }
}
