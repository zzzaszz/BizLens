package zzz.service.forum.service;

import zzz.service.forum.domain.AiKnowledgeSourceEntity;
import zzz.service.forum.domain.dto.AiKnowledgeSourceRequest;
import zzz.service.forum.domain.dto.PageResult;

import java.util.List;
import java.util.Map;

public interface AiKnowledgeSourceService {
    List<AiKnowledgeSourceEntity> listByUser(Long userId);

    PageResult<AiKnowledgeSourceEntity> pageByUser(Long userId, int page, int pageSize);

    AiKnowledgeSourceEntity getById(Long id);

    AiKnowledgeSourceEntity getOwned(Long userId, Long id);

    AiKnowledgeSourceEntity create(Long userId, AiKnowledgeSourceRequest request);

    AiKnowledgeSourceEntity update(Long userId, AiKnowledgeSourceRequest request);

    AiKnowledgeSourceEntity queueIndex(Long userId, Long id);

    Map<String, Object> queueIndexAll(Long userId);

    boolean delete(Long userId, Long id);
}
