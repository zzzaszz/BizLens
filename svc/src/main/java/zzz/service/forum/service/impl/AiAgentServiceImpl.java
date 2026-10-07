package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import zzz.service.forum.ai.AiProvider;
import zzz.service.forum.config.AiModelPresets;
import zzz.service.forum.domain.AiAgentEntity;
import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.dto.AiAgentRequest;
import zzz.service.forum.domain.dto.AiModelListRequest;
import zzz.service.forum.domain.dto.AiModelListResponse;
import zzz.service.forum.mapper.AiAgentMapper;
import zzz.service.forum.service.AiAgentService;
import zzz.service.forum.service.AiCodeProjectService;
import zzz.service.forum.utils.BaseBusinessException;
import zzz.service.forum.utils.CryptoService;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 角色服务实现类
 */
@Service("aiAgentService")
public class AiAgentServiceImpl implements AiAgentService {

    private static final Logger log = LoggerFactory.getLogger(AiAgentServiceImpl.class);

    /** 厂商标识 → 默认 base-url；custom 不预设，由用户自行填写 */
    private static final Map<String, String> DEFAULT_BASE_URL = new LinkedHashMap<>();

    static {
        DEFAULT_BASE_URL.put("deepseek", "https://api.deepseek.com");
        DEFAULT_BASE_URL.put("openai", "https://api.openai.com/v1");
        DEFAULT_BASE_URL.put("qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1");
    }

    /** 预置模型列表来自配置 ai.preset-models.*，不硬编码（模型名迭代快） */
    @Resource
    private AiModelPresets aiModelPresets;
    @Resource
    private AiAgentMapper aiAgentMapper;
    @Resource
    private AiCodeProjectService aiCodeProjectService;
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Resource
    private CryptoService cryptoService;
    @Resource
    private AiProvider aiProvider;

    @Override
    public AiAgentEntity getById(Long id) {
        return id == null ? null : aiAgentMapper.getById(id);
    }

    @Override
    public List<AiAgentEntity> listByProjectId(Long projectId) {
        return projectId == null ? Collections.emptyList() : aiAgentMapper.listByProjectId(projectId);
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
        // 业务规则一：角色必须由代码项目创建（一个项目可以配置多个角色，不再限制数量）
        Long projectId = request.getProjectId();
        if (projectId == null) {
            throw new BaseBusinessException("请先选择代码项目：角色必须由代码项目创建");
        }
        AiCodeProjectEntity project = aiCodeProjectService.getOwned(userId, projectId);
        // 业务规则二：项目必须已完成索引。知识库没内容时建了角色也答不了，
        // 与其等用户发第一条消息才报错，不如在创建这一步就拦住。
        if (!"READY".equals(project.getStatus())) {
            throw new BaseBusinessException("项目「" + project.getName() + "」尚未完成索引，请先在代码项目页排队索引");
        }
        // 业务规则三：API Key 必填，没有 Key 的角色无法发起对话
        if (request.getApiKey() == null || request.getApiKey().trim().isEmpty()) {
            throw new BaseBusinessException("请填写 API Key：角色必须配置 API Key 才能发起对话");
        }
        AiAgentEntity entity = new AiAgentEntity();
        entity.setId(snowflakeIdGenerator.nextId());
        entity.setUserid(userId);
        entity.setProjectid(projectId);
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
        // 仅创建者本人可编辑；所属项目为 1:1 绑定关系，编辑时不可改绑
        if (!userId.equals(exist.getUserid())) {
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
        if (!userId.equals(exist.getUserid())) {
            throw new BaseBusinessException("无权删除他人创建的角色");
        }
        return aiAgentMapper.delete(id) > 0;
    }

    @Override
    public AiModelListResponse listModels(Long userId, AiModelListRequest request) {
        AiModelListResponse response = new AiModelListResponse();
        if (request == null) {
            response.setSource(AiModelListResponse.SOURCE_EMPTY);
            return response;
        }
        String provider = request.getProvider() == null ? "" : request.getProvider().trim();

        // base-url：表单优先；留空按厂商取默认（custom 无默认，必须是空）
        String baseUrl = isBlank(request.getApiBaseUrl())
                ? DEFAULT_BASE_URL.get(provider)
                : request.getApiBaseUrl().trim();

        // apiKey：新建角色时表单里是明文；编辑时表单不回传，改用库里已存密文解密
        String keySource = "表单";
        String apiKey = request.getApiKey();
        if (isBlank(apiKey) && request.getAgentId() != null) {
            keySource = "角色已存";
            AiAgentEntity exist = aiAgentMapper.getById(request.getAgentId());
            if (exist != null && userId.equals(exist.getUserid())) {
                apiKey = cryptoService.decrypt(exist.getApikeyenc());
            }
        }
        if (isBlank(apiKey)) {
            keySource = "无";
        }
        log.info("拉取模型列表：provider={}, baseUrl={}, keySource={}", provider, baseUrl, keySource);

        // 没有 Key 就不去打扰厂商，直接兜底并说明原因
        if (isBlank(apiKey)) {
            fillPreset(response, provider, "尚未填写 API Key，无法从厂商接口获取模型列表，以下为预置列表");
            return done(provider, response);
        }

        // 真调厂商 /models；拿不到再回退预置——两条路都不阻断表单，但来源会在 source 里如实标出
        List<String> models = aiProvider.listModels(baseUrl, apiKey);
        if (models != null && !models.isEmpty()) {
            response.setModels(models);
            response.setSource(AiModelListResponse.SOURCE_PROVIDER);
            return done(provider, response);
        }
        fillPreset(response, provider, "调用厂商 /models 未返回模型，以下为预置列表；请检查接口地址与 API Key");
        return done(provider, response);
    }

    /** 填预置列表并标明来源；该厂商没有预置时置为 EMPTY，由前端提示手输 */
    private void fillPreset(AiModelListResponse response, String provider, String message) {
        List<String> preset = aiModelPresets.getPresetModels().get(provider);
        boolean hasPreset = preset != null && !preset.isEmpty();
        response.setModels(hasPreset ? preset : Collections.emptyList());
        response.setSource(hasPreset ? AiModelListResponse.SOURCE_PRESET : AiModelListResponse.SOURCE_EMPTY);
        response.setMessage(message);
    }

    /** 统一出口打一行结论日志，便于按 provider 直接定位走了哪条分支 */
    private AiModelListResponse done(String provider, AiModelListResponse response) {
        log.info("模型列表结果：provider={}, source={}, count={}",
                provider, response.getSource(), response.getModels().size());
        return response;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
