package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiAgentEntity;

import java.util.List;

/**
 * AI 角色表(AiAgent)表数据库访问层
 */
@Mapper
public interface AiAgentMapper {

    AiAgentEntity getById(@Param("id") Long id);

    /** 项目下的全部角色（一个项目可以配置多个角色） */
    List<AiAgentEntity> listByProjectId(@Param("projectId") Long projectId);

    /** 当前用户创建的角色（均启用） */
    List<AiAgentEntity> listForUser(@Param("userId") Long userId);

    int insert(AiAgentEntity entity);

    int update(AiAgentEntity entity);

    int delete(@Param("id") Long id);
}
