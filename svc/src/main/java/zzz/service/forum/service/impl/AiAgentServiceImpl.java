package zzz.service.forum.service.impl;

import org.springframework.stereotype.Service;
import zzz.service.forum.domain.AiAgentEntity;
import zzz.service.forum.domain.dto.AiAgentRequest;
import zzz.service.forum.mapper.AiAgentMapper;
import zzz.service.forum.service.AiAgentService;
import zzz.service.forum.utils.BaseBusinessException;
import zzz.service.forum.utils.CryptoService;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * AI 角色服务实现类
 */
@Service("aiAgentService")
public class AiAgentServiceImpl implements AiAgentService {

    @Resource
    private AiAgentMapper aiAgentMapper;
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Resource
    private CryptoService cryptoService;

    @Override
    public AiAgentEntity getById(Long id) {
        return id == null ? null : aiAgentMapper.getById(id);
    }

    @Override
    public List<AiAgentEntity> listForUser(Long userId) {
        return aiAgentMapper.listForUser(userId);
    }

    @Override
    public AiAgentEntity create(Long userId, AiAgentRequest request) {
        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new BaseBusinessException("角色名称不能为空");
        }
        AiAgentEntity entity = new AiAgentEntity();
        entity.setId(snowflakeIdGenerator.nextId());
        entity.setUserid(userId);
        entity.setName(request.getName().trim());
        entity.setCode(request.getCode());
        entity.setModel(request.getModel());
        entity.setSystemprompt(request.getSystemPrompt());
        entity.setTemperature(request.getTemperature() != null ? request.getTemperature() : new BigDecimal("0.70"));
        entity.setMaxtokens(request.getMaxTokens() != null ? request.getMaxTokens() : 2048);
        entity.setEnabled(request.getEnabled() != null ? request.getEnabled() : 1);
        entity.setIssystem(0);
        entity.setAvatar(request.getAvatar());
        entity.setProvider(request.getProvider());
        entity.setApibaseurl(request.getApiBaseUrl());
        // 用户自带的 API Key 加密后落库，不存明文
        entity.setApikeyenc(cryptoService.encrypt(request.getApiKey()));
        Date now = new Date();
        entity.setCreatedat(now);
        entity.setUpdatedat(now);
        aiAgentMapper.insert(entity);
        return entity;
    }

    @Override
    public AiAgentEntity update(Long userId, AiAgentRequest request) {
        if (request.getId() == null) {
            throw new BaseBusinessException("角色ID不能为空");
        }
        AiAgentEntity exist = aiAgentMapper.getById(request.getId());
        if (exist == null) {
            throw new BaseBusinessException("角色不存在");
        }
        // 仅创建者本人可编辑（系统角色不可编辑）
        if (exist.getIssystem() != null && exist.getIssystem() == 1) {
            throw new BaseBusinessException("系统角色不可编辑");
        }
        if (exist.getUserid() != null && !exist.getUserid().equals(userId)) {
            throw new BaseBusinessException("无权编辑他人创建的角色");
        }

        AiAgentEntity entity = new AiAgentEntity();
        entity.setId(request.getId());
        entity.setName(request.getName());
        entity.setCode(request.getCode());
        entity.setModel(request.getModel());
        entity.setSystemprompt(request.getSystemPrompt());
        entity.setTemperature(request.getTemperature());
        entity.setMaxtokens(request.getMaxTokens());
        entity.setEnabled(request.getEnabled());
        entity.setAvatar(request.getAvatar());
        entity.setProvider(request.getProvider());
        entity.setApibaseurl(request.getApiBaseUrl());
        // 编辑时 apiKey 留空表示不覆盖；非空则加密后更新
        if (request.getApiKey() != null && !request.getApiKey().isEmpty()) {
            entity.setApikeyenc(cryptoService.encrypt(request.getApiKey()));
        }
        aiAgentMapper.update(entity);
        return aiAgentMapper.getById(request.getId());
    }

    @Override
    public boolean delete(Long userId, Long id) {
        AiAgentEntity exist = aiAgentMapper.getById(id);
        if (exist == null) {
            return false;
        }
        if (exist.getIssystem() != null && exist.getIssystem() == 1) {
            throw new BaseBusinessException("系统角色不可删除");
        }
        if (exist.getUserid() != null && !exist.getUserid().equals(userId)) {
            throw new BaseBusinessException("无权删除他人创建的角色");
        }
        return aiAgentMapper.delete(id) > 0;
    }
}
