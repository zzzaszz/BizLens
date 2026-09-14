package zzz.service.forum.ai;

/**
 * 大模型返回结果
 */
public class AiResponse {

    /** 模型回复内容 */
    private String content;

    /** 是否因 Key 额度/失效失败（供角色 Key 健康监测） */
    private boolean keyError;

    public AiResponse() {
    }

    public AiResponse(String content) {
        this.content = content;
    }

    public static AiResponse of(String content) {
        return new AiResponse(content);
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isKeyError() {
        return keyError;
    }

    public void setKeyError(boolean keyError) {
        this.keyError = keyError;
    }
}
