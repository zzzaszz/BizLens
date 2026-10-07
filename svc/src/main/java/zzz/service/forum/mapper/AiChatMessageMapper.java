package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiChatMessageEntity;

import java.util.List;

/**
 * AI 消息表(AiChatMessage)表数据库访问层
 */
@Mapper
public interface AiChatMessageMapper {

    int insert(AiChatMessageEntity entity);

    /** 单条消息（删除前用来校验归属） */
    AiChatMessageEntity getById(@Param("id") Long id);

    /** 某会话全部消息（按时间正序） */
    List<AiChatMessageEntity> listByConversation(@Param("conversationId") Long conversationId);

    /** 某会话最近 N 条消息（按时间正序） */
    List<AiChatMessageEntity> selectRecent(@Param("conversationId") Long conversationId, @Param("limit") int limit);

    int deleteById(@Param("id") Long id);
}
