package zzz.service.forum.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.AiAgentEntity;
import zzz.service.forum.domain.dto.AiAgentRequest;
import zzz.service.forum.domain.dto.AiModelListRequest;
import zzz.service.forum.domain.dto.AiModelListResponse;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.service.AiAgentService;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 角色接口：系统角色 + 用户自建角色 的列表 / 创建 / 编辑 / 删除。
 * 路径约定与 forum(/forum)、chat(/chat) 一致：不带 /api 前缀（前端经 /api 代理转发）。
 */
@RestController
@RequestMapping("/ai/agent")
public class AiAgentController {

    private static final Logger logger = LoggerFactory.getLogger(AiAgentController.class);

    @Resource
    private AiAgentService aiAgentService;

    /** 角色列表（系统角色 + 我的角色），返回时脱敏 API Key */
    @GetMapping("/list")
    public R<List<AiAgentEntity>> list() {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        List<AiAgentEntity> agents = aiAgentService.listForUser(userId);
        if (agents == null) agents = new ArrayList<>();
        for (AiAgentEntity agent : agents) {
            maskApiKey(agent);
        }
        return R.ok(agents);
    }

    /**
     * 脱敏 API Key，只留尾号。
     * <p>
     * 列表、创建、编辑的响应都要过这一道：实体里带的是 AES 密文，
     * 原样返回等于把库里的密钥材料交给前端，密文本身也不该出网。
     */
    private void maskApiKey(AiAgentEntity agent) {
        if (agent == null || agent.getApikeyenc() == null || agent.getApikeyenc().isEmpty()) {
            return;
        }
        String key = agent.getApikeyenc();
        agent.setApikeyenc(key.length() > 4 ? "****" + key.substring(key.length() - 4) : "****");
    }

    /**
     * 拉取厂商可用模型列表，用于填充配置表单的模型下拉。
     * 厂商 /models 调不通时回退预置列表，始终返回 200，不阻断表单；
     * 响应里的 source 标明数据来自厂商还是预置，前端据此提示用户。
     */
    @PostMapping("/models")
    public R<AiModelListResponse> models(@RequestBody AiModelListRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        return R.ok(aiAgentService.listModels(userId, request));
    }

    /** 创建角色（TODO Phase C: 校验权限点 ai:agent:create） */
    @PostMapping("/create")
    public R<AiAgentEntity> create(@RequestBody AiAgentRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            AiAgentEntity created = aiAgentService.create(userId, request);
            maskApiKey(created);
            return R.ok(created);
        } catch (Exception e) {
            logger.warn("create agent error", e);
            return R.fail(e.getMessage());
        }
    }

    /** 编辑角色（仅本人） */
    @PostMapping("/update")
    public R<AiAgentEntity> update(@RequestBody AiAgentRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            AiAgentEntity updated = aiAgentService.update(userId, request);
            maskApiKey(updated);
            return R.ok(updated);
        } catch (Exception e) {
            logger.warn("update agent error", e);
            return R.fail(e.getMessage());
        }
    }

    /** 删除角色（仅本人，系统角色不可删） */
    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(aiAgentService.delete(userId, id));
        } catch (Exception e) {
            logger.warn("delete agent error", e);
            return R.fail(e.getMessage());
        }
    }
}
