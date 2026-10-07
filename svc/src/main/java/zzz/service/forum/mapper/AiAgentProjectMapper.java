package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.dto.AiAgentProjectRef;

import java.util.List;

/**
 * 角色 ↔ 代码项目 关联表数据库访问层（多对多）。
 * <p>
 * 一个角色可以覆盖多个项目（例如前后端共用一个角色），一个项目也可以建多个角色。
 */
@Mapper
public interface AiAgentProjectMapper {

    /** 批量建立关联；重复的关联会被忽略 */
    int insertBatch(@Param("agentId") Long agentId, @Param("projectIds") List<Long> projectIds);

    /** 清掉某角色的全部关联（编辑角色时整体替换用） */
    int deleteByAgent(@Param("agentId") Long agentId);

    /** 某角色关联的项目ID，按建立时间正序 */
    List<Long> listProjectIds(@Param("agentId") Long agentId);

    /** 某用户全部角色的项目关联，用于列表接口批量填充，避免逐个查询 */
    List<AiAgentProjectRef> listRefsByUser(@Param("userId") Long userId);
}
