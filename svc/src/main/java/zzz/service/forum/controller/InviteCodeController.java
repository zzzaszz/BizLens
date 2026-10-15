package zzz.service.forum.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.dto.InviteCodeView;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.service.InviteCodeService;
import zzz.service.forum.utils.AuthBusinessException;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/api/auth/invite")
public class InviteCodeController {

    private static final Logger logger = LoggerFactory.getLogger(InviteCodeController.class);

    @Resource
    private InviteCodeService inviteCodeService;

    @PostMapping("/create")
    public R<?> create() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return R.fail(401, "请先登录");
        }
        try {
            return R.ok(inviteCodeService.create(userId));
        } catch (AuthBusinessException e) {
            return R.info(e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            logger.warn("create invite code error: {}", e.getMessage());
            return R.fail("服务异常，请稍后重试");
        }
    }

    @GetMapping("/list")
    public R<List<InviteCodeView>> list() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return R.fail(401, "请先登录");
        }
        return R.ok(inviteCodeService.list(userId));
    }

    @PostMapping("/revoke")
    public R<?> revoke(@RequestBody RevokeRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return R.fail(401, "请先登录");
        }
        try {
            inviteCodeService.revoke(userId, request == null ? null : request.getId());
            return R.ok(null);
        } catch (AuthBusinessException e) {
            return R.info(e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            logger.warn("revoke invite code error: {}", e.getMessage());
            return R.fail("服务异常，请稍后重试");
        }
    }

    public static class RevokeRequest {
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }
    }
}
