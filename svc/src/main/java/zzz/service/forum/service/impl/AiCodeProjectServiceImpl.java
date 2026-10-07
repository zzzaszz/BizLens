package zzz.service.forum.service.impl;

import org.springframework.stereotype.Service;
import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.dto.AiCodeProjectRequest;
import zzz.service.forum.mapper.AiCodeProjectMapper;
import zzz.service.forum.service.AiCodeProjectService;
import zzz.service.forum.service.AiCodeIndexService;
import zzz.service.forum.utils.BaseBusinessException;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

@Service("aiCodeProjectService")
public class AiCodeProjectServiceImpl implements AiCodeProjectService {

    /**
     * 判定"索引任务还活着"的时间窗（分钟）。
     * 超过这个时长状态仍停在 QUEUED/INDEXING，就认为上一轮任务已随进程异常退出，
     * 允许重新排队——否则状态会永久卡住且没有任何出口。
     */
    private static final int STUCK_MINUTES = 10;

    @Resource
    private AiCodeProjectMapper projectMapper;
    @Resource
    private AiCodeIndexService indexService;
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public List<AiCodeProjectEntity> listByUser(Long userId) {
        return projectMapper.listByUser(userId);
    }

    @Override
    public AiCodeProjectEntity getById(Long projectId) {
        return projectId == null ? null : projectMapper.getById(projectId);
    }

    @Override
    public AiCodeProjectEntity getOwned(Long userId, Long projectId) {
        return requireOwner(userId, projectId);
    }

    @Override
    public AiCodeProjectEntity create(Long userId, AiCodeProjectRequest request) {
        validate(request);
        AiCodeProjectEntity entity = new AiCodeProjectEntity();
        entity.setId(snowflakeIdGenerator.nextId());
        entity.setName(request.getName().trim());
        entity.setGitUrl(request.getGitUrl().trim());
        entity.setGitBranch(normalizeBranch(request.getGitBranch()));
        entity.setCredentialRef(blankToNull(request.getCredentialRef()));
        entity.setStatus("PENDING");
        entity.setCreatedBy(userId);
        Date now = new Date();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        projectMapper.insert(entity);
        return entity;
    }

    @Override
    public AiCodeProjectEntity update(Long userId, AiCodeProjectRequest request) {
        if (request == null || request.getId() == null) {
            throw new BaseBusinessException("项目ID不能为空");
        }
        AiCodeProjectEntity exist = requireOwner(userId, request.getId());
        validate(request);
        AiCodeProjectEntity entity = new AiCodeProjectEntity();
        entity.setId(exist.getId());
        entity.setName(request.getName().trim());
        entity.setGitUrl(request.getGitUrl().trim());
        entity.setGitBranch(normalizeBranch(request.getGitBranch()));
        entity.setCredentialRef(blankToNull(request.getCredentialRef()));
        projectMapper.update(entity);
        return projectMapper.getById(entity.getId());
    }

    @Override
    public AiCodeProjectEntity queueIndex(Long userId, Long projectId) {
        AiCodeProjectEntity project = requireOwner(userId, projectId);
        // 一个项目同时跑两个索引任务会互相覆盖切片数据（都先按项目删除再写入），必须挡住
        if (isIndexRunning(project)) {
            throw new BaseBusinessException("该项目正在索引，请等本次索引结束后再重试");
        }
        projectMapper.updateStatus(projectId, "QUEUED");
        indexService.indexAsync(projectId);
        return projectMapper.getById(projectId);
    }

    /**
     * 是否已有索引任务在跑。
     * <p>
     * 只看状态不够：进程在 INDEXING 期间被强杀时状态不会回写，会永久停在 INDEXING。
     * 所以再加一道时间窗，状态新近被改动才认为任务还活着；否则视为已死，放行重排。
     */
    private boolean isIndexRunning(AiCodeProjectEntity project) {
        String status = project.getStatus();
        if (!"QUEUED".equals(status) && !"INDEXING".equals(status)) {
            return false;
        }
        Date updatedAt = project.getUpdatedAt();
        if (updatedAt == null) {
            return false;
        }
        long elapsedMinutes = (System.currentTimeMillis() - updatedAt.getTime()) / 60000L;
        // 时钟回拨导致 elapsed 为负时按"还在跑"处理，宁可让用户多等也不要并发跑两个任务
        return elapsedMinutes < STUCK_MINUTES;
    }

    @Override
    public boolean delete(Long userId, Long projectId) {
        requireOwner(userId, projectId);
        return projectMapper.delete(projectId) > 0;
    }

    private AiCodeProjectEntity requireOwner(Long userId, Long projectId) {
        if (projectId == null) {
            throw new BaseBusinessException("项目ID不能为空");
        }
        AiCodeProjectEntity entity = projectMapper.getById(projectId);
        if (entity == null) {
            throw new BaseBusinessException("代码项目不存在");
        }
        if (!userId.equals(entity.getCreatedBy())) {
            throw new BaseBusinessException("无权访问该代码项目");
        }
        return entity;
    }

    private void validate(AiCodeProjectRequest request) {
        if (request == null || isBlank(request.getName())) {
            throw new BaseBusinessException("项目名称不能为空");
        }
        if (isBlank(request.getGitUrl())) {
            throw new BaseBusinessException("Git 地址不能为空");
        }
        if (request.getGitUrl().length() > 512) {
            throw new BaseBusinessException("Git 地址长度不能超过512个字符");
        }
    }

    private String normalizeBranch(String branch) {
        return isBlank(branch) ? "main" : branch.trim();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
