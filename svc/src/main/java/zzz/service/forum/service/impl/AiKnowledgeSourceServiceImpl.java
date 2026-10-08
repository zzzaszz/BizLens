package zzz.service.forum.service.impl;

import org.springframework.stereotype.Service;
import zzz.service.forum.domain.AiKnowledgeSourceEntity;
import zzz.service.forum.domain.dto.AiKnowledgeSourceRequest;
import zzz.service.forum.domain.dto.PageResult;
import zzz.service.forum.mapper.AiKnowledgeSourceMapper;
import zzz.service.forum.service.AiKnowledgeIndexService;
import zzz.service.forum.service.AiKnowledgeSourceService;
import zzz.service.forum.utils.BaseBusinessException;
import zzz.service.forum.utils.CryptoService;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service("aiKnowledgeSourceService")
public class AiKnowledgeSourceServiceImpl implements AiKnowledgeSourceService {
    private static final int STUCK_SECONDS = 180;

    @Resource
    private AiKnowledgeSourceMapper sourceMapper;
    @Resource
    private AiKnowledgeIndexService indexService;
    @Resource
    private CryptoService cryptoService;
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public List<AiKnowledgeSourceEntity> listByUser(Long userId) {
        return sourceMapper.listByUser(userId);
    }

    @Override
    public PageResult<AiKnowledgeSourceEntity> pageByUser(Long userId, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long offset = (long) (safePage - 1) * safeSize;
        return new PageResult<>(sourceMapper.pageByUser(userId, offset, safeSize),
                sourceMapper.countByUser(userId), safePage, safeSize);
    }

    @Override
    public AiKnowledgeSourceEntity getById(Long id) {
        return id == null ? null : sourceMapper.getById(id);
    }

    @Override
    public AiKnowledgeSourceEntity getOwned(Long userId, Long id) {
        AiKnowledgeSourceEntity source = requireOwner(userId, id);
        return source;
    }

    @Override
    public AiKnowledgeSourceEntity create(Long userId, AiKnowledgeSourceRequest request) {
        validate(request);
        AiKnowledgeSourceEntity entity = new AiKnowledgeSourceEntity();
        entity.setId(snowflakeIdGenerator.nextId());
        entity.setName(request.getName().trim());
        entity.setSourceType("GIT_MARKDOWN");
        entity.setGitUrl(request.getGitUrl().trim());
        entity.setGitBranch(normalize(request.getGitBranch(), "main"));
        entity.setRootPath(normalize(request.getRootPath(), ""));
        entity.setCredentialEnc(cryptoService.encrypt(blankToNull(request.getCredentialToken())));
        entity.setCredentialUser(blankToNull(request.getCredentialUser()));
        entity.setStatus("PENDING");
        entity.setCreatedBy(userId);
        Date now = new Date();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        sourceMapper.insert(entity);
        return entity;
    }

    @Override
    public AiKnowledgeSourceEntity update(Long userId, AiKnowledgeSourceRequest request) {
        if (request == null || request.getId() == null) throw new BaseBusinessException("知识库ID不能为空");
        AiKnowledgeSourceEntity existing = requireOwner(userId, request.getId());
        validate(request);
        AiKnowledgeSourceEntity entity = new AiKnowledgeSourceEntity();
        entity.setId(existing.getId());
        entity.setName(request.getName().trim());
        entity.setGitUrl(request.getGitUrl().trim());
        entity.setGitBranch(normalize(request.getGitBranch(), "main"));
        entity.setRootPath(normalize(request.getRootPath(), ""));
        String token = blankToNull(request.getCredentialToken());
        if (token != null) entity.setCredentialEnc(cryptoService.encrypt(token));
        entity.setCredentialUser(blankToNull(request.getCredentialUser()));
        sourceMapper.update(entity);
        return sourceMapper.getById(entity.getId());
    }

    @Override
    public AiKnowledgeSourceEntity queueIndex(Long userId, Long id) {
        AiKnowledgeSourceEntity source = requireOwner(userId, id);
        if (isIndexRunning(source)) throw new BaseBusinessException("该知识库正在索引，请等本次索引结束后再重试");
        sourceMapper.updateStatus(id, "QUEUED");
        indexService.indexAsync(id);
        return sourceMapper.getById(id);
    }

    @Override
    public Map<String, Object> queueIndexAll(Long userId) {
        List<AiKnowledgeSourceEntity> sources = sourceMapper.listByUser(userId);
        List<String> skippedNames = new ArrayList<>();
        int queued = 0;
        for (AiKnowledgeSourceEntity source : sources) {
            if (isIndexRunning(source)) {
                skippedNames.add(source.getName());
                continue;
            }
            sourceMapper.updateStatus(source.getId(), "QUEUED");
            indexService.indexAsync(source.getId());
            queued++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", sources.size());
        result.put("queued", queued);
        result.put("skipped", skippedNames.size());
        result.put("skippedNames", skippedNames);
        return result;
    }

    @Override
    public boolean delete(Long userId, Long id) {
        requireOwner(userId, id);
        return sourceMapper.delete(id) > 0;
    }

    private AiKnowledgeSourceEntity requireOwner(Long userId, Long id) {
        if (id == null) throw new BaseBusinessException("知识库ID不能为空");
        AiKnowledgeSourceEntity source = sourceMapper.getById(id);
        if (source == null) throw new BaseBusinessException("知识库不存在");
        if (!userId.equals(source.getCreatedBy())) throw new BaseBusinessException("无权访问该知识库");
        return source;
    }

    private boolean isIndexRunning(AiKnowledgeSourceEntity source) {
        if (!"QUEUED".equals(source.getStatus()) && !"INDEXING".equals(source.getStatus())) return false;
        if (source.getUpdatedAt() == null) return false;
        return (System.currentTimeMillis() - source.getUpdatedAt().getTime()) / 1000L < STUCK_SECONDS;
    }

    private void validate(AiKnowledgeSourceRequest request) {
        if (request == null || isBlank(request.getName())) throw new BaseBusinessException("知识库名称不能为空");
        if (isBlank(request.getGitUrl())) throw new BaseBusinessException("Git 地址不能为空");
        if (request.getGitUrl().length() > 512) throw new BaseBusinessException("Git 地址长度不能超过512个字符");
        if (request.getRootPath() != null && request.getRootPath().length() > 256) throw new BaseBusinessException("索引子目录长度不能超过256个字符");
    }

    private String normalize(String value, String fallback) { return isBlank(value) ? fallback : value.trim(); }
    private String blankToNull(String value) { return isBlank(value) ? null : value.trim(); }
    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
