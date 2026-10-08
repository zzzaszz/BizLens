package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiSecurityAuditEntity;

@Mapper
public interface AiSecurityAuditMapper {

    @Insert("INSERT INTO ai_security_audit "
            + "(id, user_id, project_id, question, retrieval_meta, model, response_status, "
            + "triggered_rules, blocked, created_at) "
            + "VALUES (#{id}, #{userId}, #{projectId}, #{question}, CAST(#{retrievalMeta} AS jsonb), "
            + "#{model}, #{responseStatus}, CAST(#{triggeredRules} AS jsonb), #{blocked}, #{createdAt})")
    int insert(AiSecurityAuditEntity entity);
}
