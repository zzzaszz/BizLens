package zzz.service.forum.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.AiKnowledgeSourceEntity;
import zzz.service.forum.domain.dto.AiKnowledgeSourceRequest;
import zzz.service.forum.domain.dto.PageResult;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.service.AiKnowledgeSourceService;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/ai/knowledge")
public class AiKnowledgeSourceController {
    private static final Logger log = LoggerFactory.getLogger(AiKnowledgeSourceController.class);

    @Resource
    private AiKnowledgeSourceService sourceService;

    @GetMapping("/list")
    public R<?> list(@RequestParam(required = false) Integer page,
                     @RequestParam(required = false) Integer pageSize) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        if (page != null || pageSize != null) {
            PageResult<AiKnowledgeSourceEntity> result = sourceService.pageByUser(userId,
                    page == null ? 1 : page, pageSize == null ? 20 : pageSize);
            result.getRecords().forEach(this::maskCredential);
            return R.ok(result);
        }
        List<AiKnowledgeSourceEntity> sources = sourceService.listByUser(userId);
        sources.forEach(this::maskCredential);
        return R.ok(sources);
    }

    @PostMapping("/create")
    public R<AiKnowledgeSourceEntity> create(@RequestBody AiKnowledgeSourceRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            AiKnowledgeSourceEntity source = sourceService.create(userId, request);
            maskCredential(source);
            return R.ok(source);
        } catch (Exception e) {
            log.warn("create knowledge source error", e);
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/update")
    public R<AiKnowledgeSourceEntity> update(@RequestBody AiKnowledgeSourceRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            AiKnowledgeSourceEntity source = sourceService.update(userId, request);
            maskCredential(source);
            return R.ok(source);
        } catch (Exception e) {
            log.warn("update knowledge source error", e);
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/index")
    public R<AiKnowledgeSourceEntity> queueIndex(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try { return R.ok(sourceService.queueIndex(userId, id), "索引任务已排队"); }
        catch (Exception e) { log.warn("queue knowledge index error", e); return R.fail(e.getMessage()); }
    }

    @PostMapping("/indexAll")
    public R<Map<String, Object>> queueIndexAll() {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try { return R.ok(sourceService.queueIndexAll(userId)); }
        catch (Exception e) { log.warn("queue all knowledge indexes error", e); return R.fail(e.getMessage()); }
    }

    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try { return R.ok(sourceService.delete(userId, id)); }
        catch (Exception e) { log.warn("delete knowledge source error", e); return R.fail(e.getMessage()); }
    }

    private void maskCredential(AiKnowledgeSourceEntity source) {
        if (source == null || source.getCredentialEnc() == null || source.getCredentialEnc().isEmpty()) return;
        String value = source.getCredentialEnc();
        source.setCredentialEnc(value.length() > 4 ? "****" + value.substring(value.length() - 4) : "****");
    }
}
