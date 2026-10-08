package zzz.service.forum.service;

import zzz.service.forum.domain.AiConversationEntity;
import zzz.service.forum.domain.dto.PageResult;

import java.util.List;

/**
 * AI 会话服务接口
 */
public interface AiConversationService {

    AiConversationEntity getById(Long id);

    List<AiConversationEntity> listByUser(Long userId);

    PageResult<AiConversationEntity> pageByUser(Long userId, int page, int pageSize, Integer status);

    AiConversationEntity create(Long userId, Long agentId, String title);

    /** 关闭会话（校验归属） */
    boolean close(Long conversationId, Long userId);

    /** 重新打开已关闭的会话，使其可继续对话（校验归属） */
    boolean reopen(Long conversationId, Long userId);

    /** 删除会话及其全部消息（校验归属） */
    boolean delete(Long conversationId, Long userId);
}
