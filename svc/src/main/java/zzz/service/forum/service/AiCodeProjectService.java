package zzz.service.forum.service;

import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.dto.AiCodeProjectRequest;
import zzz.service.forum.domain.dto.PageResult;

import java.util.List;

public interface AiCodeProjectService {
    List<AiCodeProjectEntity> listByUser(Long userId);

    PageResult<AiCodeProjectEntity> pageByUser(Long userId, int page, int pageSize);

    /** 按主键查询（不校验归属，调用方自行判断） */
    AiCodeProjectEntity getById(Long projectId);

    /** 按主键查询并校验归属当前用户 */
    AiCodeProjectEntity getOwned(Long userId, Long projectId);

    AiCodeProjectEntity create(Long userId, AiCodeProjectRequest request);

    AiCodeProjectEntity update(Long userId, AiCodeProjectRequest request);

    AiCodeProjectEntity queueIndex(Long userId, Long projectId);

    AiCodeProjectEntity cancelIndex(Long userId, Long projectId);

    /**
     * 一键把当前用户的全部项目重新排队索引。
     * 正在索引的项目会跳过（同一项目跑两个索引任务会互相覆盖切片数据）。
     *
     * @return { total, queued, skipped, skippedNames }
     */
    java.util.Map<String, Object> queueIndexAll(Long userId);

    boolean delete(Long userId, Long projectId);
}
