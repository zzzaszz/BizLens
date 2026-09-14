package zzz.service.forum.ai;

import java.util.List;

/**
 * 发给大模型的请求体
 */
public class AiRequest {

    /** 模型标识 */
    private String model;
    /** 消息列表（system + history + 当前问题） */
    private List<AiMessage> messages;
    /** 采样温度（可空，使用系统默认） */
    private Double temperature;
    /** 最大输出 token（可空） */
    private Integer maxTokens;

    /** 以下为调用方按需覆盖（角色自带 Key 时使用） */
    private String provider;
    private String baseUrl;
    private String apiKey;

    public AiRequest() {
    }

    public AiRequest(String model, List<AiMessage> messages) {
        this.model = model;
        this.messages = messages;
    }

    public static AiRequest of(String model, List<AiMessage> messages) {
        return new AiRequest(model, messages);
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<AiMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AiMessage> messages) {
        this.messages = messages;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
