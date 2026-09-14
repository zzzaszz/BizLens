package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiConversationEntity;

import java.util.List;

/**
 * AI 会话表(AiConversation)表数据库访问层
 */
@Mapper
public interface AiConversationMapper {

    AiConversationEntity getById(@Param("id") Long id);

    List<AiConversationEntity> listByUser(@Param("userId") Long userId);

    int insert(AiConversationEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status);
}
