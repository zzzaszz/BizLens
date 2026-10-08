package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.dto.AiAgentKnowledgeRef;

import java.util.List;

@Mapper
public interface AiAgentKnowledgeMapper {
    int insertBatch(@Param("agentId") Long agentId, @Param("knowledgeIds") List<Long> knowledgeIds);

    int deleteByAgent(@Param("agentId") Long agentId);

    List<Long> listKnowledgeIds(@Param("agentId") Long agentId);

    List<AiAgentKnowledgeRef> listRefsByUser(@Param("userId") Long userId);
}
