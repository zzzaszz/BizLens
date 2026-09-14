package zzz.service.forum.service;

import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.dto.AiCodeProjectRequest;

import java.util.List;

public interface AiCodeProjectService {
    List<AiCodeProjectEntity> listByUser(Long userId);

    AiCodeProjectEntity create(Long userId, AiCodeProjectRequest request);

    AiCodeProjectEntity update(Long userId, AiCodeProjectRequest request);

    AiCodeProjectEntity queueIndex(Long userId, Long projectId);

    boolean delete(Long userId, Long projectId);
}
