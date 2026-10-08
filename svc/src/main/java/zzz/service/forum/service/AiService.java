package zzz.service.forum.service;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zzz.service.forum.domain.AiChatMessageEntity;
import zzz.service.forum.domain.AiConversationEntity;
import zzz.service.forum.domain.dto.AiCreateConversationRequest;
import zzz.service.forum.domain.dto.AiSendRequest;
import zzz.service.forum.domain.dto.PageResult;

import java.util.List;
import java.util.Map;

/**
 * AI 对话业务服务接口：会话管理 + 发送消息 + 历史记录
 */
public interface AiService {

    /** 创建 AI 会话 */
    AiConversationEntity createConversation(Long userId, AiCreateConversationRequest request);

    /** 我的会话列表 */
    List<AiConversationEntity> listConversations(Long userId);

    PageResult<AiConversationEntity> pageConversations(Long userId, int page, int pageSize, Integer status);

    /** 关闭会话 */
    boolean closeConversation(Long userId, Long conversationId);

    /**
     * 重新打开已关闭的会话，使其可继续对话。
     * 打开历史会话时用：历史随时可看，但要接着聊必须先"续上"。
     */
    boolean reopenConversation(Long userId, Long conversationId);

    /**
     * 发送消息并获取 AI 回复。
     *
     * @return { conversationId, reply, history }
     */
    Map<String, Object> chat(Long userId, AiSendRequest request);

    /**
     * 流式发送消息：模型增量逐段推送到 SseEmitter，完成后落库。
     */
    void chatStream(Long userId, AiSendRequest request, SseEmitter emitter);

    /** 会话历史消息 */
    List<AiChatMessageEntity> history(Long userId, Long conversationId);

    /**
     * 删除单条消息（校验该消息所属会话归当前用户）。
     *
     * @return 是否删除成功
     */
    boolean deleteMessage(Long userId, Long messageId);

    /**
     * 删除整个会话及其全部消息（校验归属）。
     * 与「关闭会话」不同：关闭只是归档，删除不可恢复。
     */
    boolean deleteConversation(Long userId, Long conversationId);
}
