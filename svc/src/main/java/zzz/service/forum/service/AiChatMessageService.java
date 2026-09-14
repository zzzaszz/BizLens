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
}
