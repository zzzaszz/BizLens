package zzz.service.forum.service;

import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.dto.AiCodeProjectRequest;

import java.util.List;

public interface AiCodeProjectService {
    List<AiCodeProjectEntity> listByUser(Long userId);

    /** 按主键查询（不校验归属，调用方自行判断） */
    AiCodeProjectEntity getById(Long projectId);

    /** 按主键查询并校验归属当前用户 */
    AiCodeProjectEntity getOwned(Long userId, Long projectId);

    AiCodeProjectEntity create(Long userId, AiCodeProjectRequest request);

    AiCodeProjectEntity update(Long userId, AiCodeProjectRequest request);

    AiCodeProjectEntity queueIndex(Long userId, Long projectId);

    boolean delete(Long userId, Long projectId);
}
