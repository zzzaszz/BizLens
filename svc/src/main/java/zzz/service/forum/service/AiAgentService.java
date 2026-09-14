package zzz.service.forum.service;

import zzz.service.forum.domain.AiAgentEntity;
import zzz.service.forum.domain.dto.AiAgentRequest;

import java.util.List;

/**
 * AI 角色服务接口
 */
public interface AiAgentService {

    AiAgentEntity getById(Long id);

    /** 系统角色 + 当前用户自建角色 */
    List<AiAgentEntity> listForUser(Long userId);

    /** 创建角色（当前用户） */
    AiAgentEntity create(Long userId, AiAgentRequest request);

    /** 编辑角色（校验归属） */
    AiAgentEntity update(Long userId, AiAgentRequest request);

    /** 删除角色（校验归属，仅自建角色） */
    boolean delete(Long userId, Long id);
}
