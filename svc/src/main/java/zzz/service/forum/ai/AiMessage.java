package zzz.service.forum.ai;

/**
 * 发给大模型的单条消息（OpenAI 兼容格式）
 */
public class AiMessage {

    /** role: system / user / assistant */
    private String role;
    /** 内容 */
    private String content;

    public AiMessage() {
    }

    public AiMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static AiMessage system(String content) {
        return new AiMessage("system", content);
    }

    public static AiMessage user(String content) {
        return new AiMessage("user", content);
    }

    public static AiMessage assistant(String content) {
        return new AiMessage("assistant", content);
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
