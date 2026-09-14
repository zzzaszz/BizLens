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

    /** 项目绑定的角色（一个项目最多一个，由 uk_ai_agent_project 保证） */
    AiAgentEntity getByProjectId(@Param("projectId") Long projectId);

    /** 当前用户创建的角色（均启用） */
    List<AiAgentEntity> listForUser(@Param("userId") Long userId);

    int insert(AiAgentEntity entity);

    int update(AiAgentEntity entity);

    int delete(@Param("id") Long id);
}
