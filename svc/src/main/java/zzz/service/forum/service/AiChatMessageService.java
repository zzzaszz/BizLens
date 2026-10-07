package zzz.service.forum.service;

import zzz.service.forum.domain.AiChatMessageEntity;

import java.util.List;

/**
 * AI 消息服务接口
 */
public interface AiChatMessageService {

    AiChatMessageEntity save(Long conversationId, String senderType, String senderId, String content);

    List<AiChatMessageEntity> listByConversation(Long conversationId);

    /** 最近 N 条消息（按时间正序），用于组装模型上下文 */
    List<AiChatMessageEntity> loadHistory(Long conversationId, int limit);

    /** 保存消息并同步更新 Redis 热上下文（只保留最近 MAX 条） */
    AiChatMessageEntity saveAndCache(Long conversationId, String senderType, String senderId, String content);

    /** 从 Redis 热上下文读取最近消息；Miss 时查 PostgreSQL 回填 */
    List<AiChatMessageEntity> loadHistoryCached(Long conversationId, int limit);

    /** 单条消息（不存在返回 null） */
    AiChatMessageEntity getById(Long id);

    /**
     * 删除单条消息。
     * <p>
     * 必须同时清掉该会话的 Redis 热上下文：热上下文是拼模型上下文的直接来源，
     * 只删库不删缓存的话，被删的消息下一轮还会作为上下文发给模型。
     *
     * @return 被删消息所属的会话ID，消息不存在时返回 null
     */
    Long delete(Long messageId);

    /** 清掉某会话的 Redis 热上下文（会话整体被删除时调用） */
    void clearCache(Long conversationId);
}
