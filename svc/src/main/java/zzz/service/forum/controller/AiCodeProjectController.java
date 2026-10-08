package zzz.service.forum.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.dto.AiCodeProjectRequest;
import zzz.service.forum.domain.dto.PageResult;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.service.AiCodeProjectService;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/** 代码项目元数据管理；索引执行由后续异步任务负责。 */
@RestController
@RequestMapping("/ai/project")
public class AiCodeProjectController {

    private static final Logger logger = LoggerFactory.getLogger(AiCodeProjectController.class);

    @Resource
    private AiCodeProjectService projectService;

    @GetMapping("/list")
    public R<?> list(@RequestParam(required = false) Integer page,
                     @RequestParam(required = false) Integer pageSize) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        if (page != null || pageSize != null) {
            PageResult<AiCodeProjectEntity> result = projectService.pageByUser(userId,
                    page == null ? 1 : page, pageSize == null ? 20 : pageSize);
            result.getRecords().forEach(this::maskCredential);
            return R.ok(result);
        }
        List<AiCodeProjectEntity> projects = projectService.listByUser(userId);
        for (AiCodeProjectEntity project : projects) {
            maskCredential(project);
        }
        return R.ok(projects);
    }

    /**
     * 脱敏访问令牌，只留尾号。
     * <p>
     * 实体里带的是 AES 密文，原样返回等于把库里的凭据材料交给前端；
     * 前端只需要知道"配没配"，所以统一脱敏，与角色 API Key 的处理保持一致。
     */
    private void maskCredential(AiCodeProjectEntity project) {
        if (project == null || project.getCredentialEnc() == null || project.getCredentialEnc().isEmpty()) {
            return;
        }
        String enc = project.getCredentialEnc();
        project.setCredentialEnc(enc.length() > 4 ? "****" + enc.substring(enc.length() - 4) : "****");
    }

    @PostMapping("/create")
    public R<AiCodeProjectEntity> create(@RequestBody AiCodeProjectRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            AiCodeProjectEntity created = projectService.create(userId, request);
            maskCredential(created);
            return R.ok(created);
        } catch (Exception e) {
            logger.warn("create code project error", e);
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/update")
    public R<AiCodeProjectEntity> update(@RequestBody AiCodeProjectRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            AiCodeProjectEntity updated = projectService.update(userId, request);
            maskCredential(updated);
            return R.ok(updated);
        } catch (Exception e) {
            logger.warn("update code project error", e);
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/index")
    public R<AiCodeProjectEntity> queueIndex(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(projectService.queueIndex(userId, id), "索引任务已排队");
        } catch (Exception e) {
            logger.warn("queue code project index error", e);
            return R.fail(e.getMessage());
        }
    }

    /** 一键把当前用户的全部项目重新排队索引；正在索引的会跳过 */
    @PostMapping("/indexAll")
    public R<Map<String, Object>> queueIndexAll() {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(projectService.queueIndexAll(userId));
        } catch (Exception e) {
            logger.warn("queue all code projects index error", e);
            return R.fail(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(projectService.delete(userId, id));
        } catch (Exception e) {
            logger.warn("delete code project error", e);
            return R.fail(e.getMessage());
        }
    }
}
