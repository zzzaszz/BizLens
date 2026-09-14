package zzz.service.forum.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.dto.AiCodeProjectRequest;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.service.AiCodeProjectService;

import javax.annotation.Resource;
import java.util.List;

/** 代码项目元数据管理；索引执行由后续异步任务负责。 */
@RestController
@RequestMapping("/ai/project")
public class AiCodeProjectController {

    private static final Logger logger = LoggerFactory.getLogger(AiCodeProjectController.class);

    @Resource
    private AiCodeProjectService projectService;

    @GetMapping("/list")
    public R<List<AiCodeProjectEntity>> list() {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        return R.ok(projectService.listByUser(userId));
    }

    @PostMapping("/create")
    public R<AiCodeProjectEntity> create(@RequestBody AiCodeProjectRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(projectService.create(userId, request));
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
            return R.ok(projectService.update(userId, request));
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
