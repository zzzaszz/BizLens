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
        requireOwner(userId, projectId);
        projectMapper.updateStatus(projectId, "QUEUED");
        indexService.indexAsync(projectId);
        return projectMapper.getById(projectId);
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
