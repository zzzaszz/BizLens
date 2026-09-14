package zzz.service.forum.service;

import zzz.service.forum.domain.AiConversationEntity;

import java.util.List;

/**
 * AI 会话服务接口
 */
public interface AiConversationService {

    AiConversationEntity getById(Long id);

    List<AiConversationEntity> listByUser(Long userId);

    AiConversationEntity create(Long userId, Long agentId, String title);

    /** 关闭会话（校验归属） */
    boolean close(Long conversationId, Long userId);
}
