package zzz.service.forum.service.impl;

import org.springframework.stereotype.Service;
import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.dto.AiCodeProjectRequest;
import zzz.service.forum.domain.dto.PageResult;
import zzz.service.forum.mapper.AiCodeProjectMapper;
import zzz.service.forum.service.AiCodeProjectService;
import zzz.service.forum.service.AiCodeIndexService;
import zzz.service.forum.utils.BaseBusinessException;
import zzz.service.forum.utils.CryptoService;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service("aiCodeProjectService")
public class AiCodeProjectServiceImpl implements AiCodeProjectService {

    @Resource
    private CryptoService cryptoService;

    /**
     * 判定"索引任务还活着"的时间窗（秒）。
     * <p>
     * 必须与 {@code AiCodeIndexServiceImpl.HEARTBEAT_GRACE_SECONDS} 一致：
     * 索引运行期间每 30 秒会推一次心跳，所以超过这个时长没动静就说明进程已经没了。
     * 两处取不同值时会出现"前端显示可重新索引、后端却拒绝"的矛盾。
     */
    private static final int STUCK_SECONDS = 180;

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
    public PageResult<AiCodeProjectEntity> pageByUser(Long userId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long offset = (long) (safePage - 1) * safeSize;
        return new PageResult<>(projectMapper.pageByUser(userId, offset, safeSize),
                projectMapper.countByUser(userId), safePage, safeSize);
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
        // 令牌加密后落库；公开仓库留空即可
        entity.setCredentialEnc(cryptoService.encrypt(blankToNull(request.getCredentialToken())));
        entity.setCredentialUser(blankToNull(request.getCredentialUser()));
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
        // 令牌留空表示不修改（编辑时前端不回显明文）
        String token = blankToNull(request.getCredentialToken());
        if (token != null) {
            entity.setCredentialEnc(cryptoService.encrypt(token));
        }
        // 用户名总是覆盖：mapper 里 null 会被跳过，所以用空串表达"清空、回落到平台默认"
        String user = blankToNull(request.getCredentialUser());
        entity.setCredentialUser(user == null ? "" : user);
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

    @Override
    public Map<String, Object> queueIndexAll(Long userId) {
        List<AiCodeProjectEntity> projects = projectMapper.listByUser(userId);
        List<String> skippedNames = new ArrayList<>();
        int queued = 0;
        for (AiCodeProjectEntity project : projects) {
            // 正在索引的跳过：同一个项目并发跑两个索引任务，两次都会"先按项目删再全量写"，互相覆盖
            if (isIndexRunning(project)) {
                skippedNames.add(project.getName());
                continue;
            }
            projectMapper.updateStatus(project.getId(), "QUEUED");
            indexService.indexAsync(project.getId());
            queued++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", projects.size());
        result.put("queued", queued);
        result.put("skipped", skippedNames.size());
        result.put("skippedNames", skippedNames);
        return result;
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
        long elapsedSeconds = (System.currentTimeMillis() - updatedAt.getTime()) / 1000L;
        // 时钟回拨导致 elapsed 为负时按"还在跑"处理，宁可让用户多等也不要并发跑两个任务
        return elapsedSeconds < STUCK_SECONDS;
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
