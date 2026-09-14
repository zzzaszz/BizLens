package zzz.service.forum.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.User;
import zzz.service.forum.domain.dto.LoginRequest;
import zzz.service.forum.domain.dto.LogoutRequest;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.domain.dto.RefreshRequest;
import zzz.service.forum.domain.dto.RegisterRequest;
import zzz.service.forum.domain.vo.LoginResponse;
import zzz.service.forum.mapper.UserMapper;
import zzz.service.forum.service.AuthService;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    @Resource
    AuthService authService;

    @Resource
    private UserMapper userMapper;

    /**
     * 账号密码登录
     */
    @PostMapping("/login")
    public R<LoginResponse> login(@RequestBody LoginRequest request,
                                  HttpServletRequest httpServletRequest) {
        try {
            return R.ok(authService.login(request, httpServletRequest));
        } catch (Exception e) {
            logger.warn("login error", e);
            return R.fail(e.getMessage());
        }
    }

    /**
     * 账号密码注册
     */
    @PostMapping("/register")
    public R<LoginResponse> register(@RequestBody RegisterRequest request,
                                     HttpServletRequest httpServletRequest) {
        try {
            return R.ok(authService.register(request, httpServletRequest));
        } catch (Exception e) {
            logger.warn("register error", e);
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/refresh")
    public R<LoginResponse> refresh(@RequestBody RefreshRequest request) {
        try {
            return R.ok(authService.refresh(request));
        } catch (Exception e) {
            logger.warn("refresh error", e);
            return R.fail(e.getMessage());
        }
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestBody LogoutRequest request) {
        authService.logout(request.getAccessToken(), request.getRefreshToken());
        return R.ok(null);
    }

    /**
     * 更新当前用户个人资料（昵称、主题颜色等）
     */
    @PostMapping("/updateProfile")
    public R<Void> updateProfile(@RequestBody Map<String, Object> body) {
        try {
            Long userId = UserContext.getUserId();
            if (userId == null) {
                return R.fail("未登录");
            }
            String nickname = str(body.get("nickname"));
            String themeColor = str(body.get("themeColor"));
            userMapper.updateUserProfile(userId, nickname, themeColor);
            return R.ok(null);
        } catch (Exception e) {
            logger.warn("updateProfile error", e);
            return R.fail(e.getMessage());
        }
    }

    /** 安全字符串转换 */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }
}
