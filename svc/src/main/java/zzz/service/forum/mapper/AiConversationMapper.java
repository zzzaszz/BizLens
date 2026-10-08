package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiConversationEntity;

import java.util.Date;
import java.util.List;

/**
 * AI 会话表(AiConversation)表数据库访问层
 */
@Mapper
public interface AiConversationMapper {

    AiConversationEntity getById(@Param("id") Long id);

    List<AiConversationEntity> listByUser(@Param("userId") Long userId);

    List<AiConversationEntity> pageByUser(@Param("userId") Long userId,
                                         @Param("offset") long offset,
                                         @Param("pageSize") int pageSize,
                                         @Param("status") Integer status);

    long countByUser(@Param("userId") Long userId, @Param("status") Integer status);

    int insert(AiConversationEntity entity);

    /** 删除会话；其消息由外键 ON DELETE CASCADE 一并清理 */
    int deleteById(@Param("id") Long id);

    int updateStatus(@Param("id") Long id, @Param("status") Integer status, @Param("updatedAt") Date updatedAt);

    int bindAgent(@Param("id") Long id, @Param("agentId") Long agentId, @Param("updatedAt") Date updatedAt);

    /** 有新消息时推进会话的 UpdatedAt，会话列表按它倒序、界面也显示它 */
    int touch(@Param("id") Long id, @Param("updatedAt") Date updatedAt);
}
