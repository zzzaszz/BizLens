package zzz.service.forum.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.User;
import zzz.service.forum.domain.dto.ChangePasswordRequest;
import zzz.service.forum.domain.dto.LoginRequest;
import zzz.service.forum.domain.dto.LogoutRequest;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.domain.dto.RefreshRequest;
import zzz.service.forum.domain.dto.RegisterRequest;
import zzz.service.forum.domain.vo.LoginResponse;
import zzz.service.forum.mapper.UserMapper;
import zzz.service.forum.service.AuthService;
import zzz.service.forum.service.RedisService;
import zzz.service.forum.utils.AuthBusinessException;
import zzz.service.forum.utils.ClientIpResolver;
import zzz.service.forum.utils.PasswordUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private static final String CAPTCHA_PREFIX = "login:captcha:";
    private static final String CAPTCHA_RATE_PREFIX = "login:captcha:limit:";
    private static final String CAPTCHA_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource
    AuthService authService;

    @Resource
    private RedisService redisService;

    @Resource
    private UserMapper userMapper;

    @Value("${auth.trusted-proxy-ips:}")
    private String trustedProxyIps;

    /**
     * 账号密码登录
     */
    @PostMapping("/login")
    public R<?> login(@RequestBody LoginRequest request,
                      HttpServletRequest httpServletRequest) {
        try {
            return R.ok(authService.login(request, httpServletRequest));
        } catch (AuthBusinessException e) {
            logger.info("login challenge: code={}, message={}", e.getCode(), e.getMessage());
            return R.info(e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            logger.warn("login error: {}", e.getMessage());
            return R.fail(e.getMessage());
        }
    }

    /**
     * 账号密码注册
     */
    @PostMapping("/register")
    public R<?> register(@RequestBody RegisterRequest request,
                         HttpServletRequest httpServletRequest) {
        try {
            return R.ok(authService.register(request, httpServletRequest));
        } catch (AuthBusinessException e) {
            return R.info(e.getCode(), e.getMessage(), e.getData());
        } catch (Exception e) {
            logger.warn("register error: {}", e.getMessage());
            return R.fail(e.getMessage());
        }
    }

    /**
     * 生成免登录图形验证码。答案只存 Redis，不出现在响应中。
     */
    @GetMapping("/captcha")
    public R<Map<String, String>> captcha(HttpServletRequest request,
                                          HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");

        String ip = ClientIpResolver.resolve(request, trustedProxyIps);
        Long requestCount = redisService.incrementAndExpire(
                CAPTCHA_RATE_PREFIX + ip, 1, 60);
        if (requestCount == null) {
            return R.fail("服务暂时不可用，请稍后重试");
        }
        if (requestCount > 30) {
            return R.fail("验证码请求过于频繁，请稍后再试");
        }

        String answer = randomAnswer();
        String captchaId = java.util.UUID.randomUUID().toString().replace("-", "");
        redisService.setCacheObject(CAPTCHA_PREFIX + captchaId, answer, 5L,
                java.util.concurrent.TimeUnit.MINUTES);

        Map<String, String> data = new HashMap<>();
        data.put("captchaId", captchaId);
        data.put("svg", buildCaptchaSvg(answer));
        return R.ok(data);
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
            String nickname = body == null ? null : str(body.get("nickname"));
            String themeColor = body == null ? null : str(body.get("themeColor"));
            if (nickname != null && (nickname.isEmpty() || nickname.length() > 32)) {
                return R.fail("昵称长度应为 1-32 个字符");
            }
            if (themeColor != null && !themeColor.isEmpty()
                    && !themeColor.matches("^#[0-9A-Fa-f]{6}$")) {
                return R.fail("主题色格式不正确");
            }
            userMapper.updateUserProfile(userId, nickname, themeColor);
            return R.ok(null);
        } catch (Exception e) {
            logger.warn("updateProfile error", e);
            return R.fail(e.getMessage());
        }
    }

    @GetMapping("/getInfo")
    public R<Map<String, Object>> getInfo() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return R.fail(401, "用户不存在");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("avatar", user.getAvatar());
        data.put("themeColor", user.getThemeColor());
        data.put("status", user.getStatus());
        data.put("role", user.getRole());
        data.put("lastLoginIp", user.getLastLoginIp());
        data.put("lastLoginAt", user.getLastLoginAt());
        data.put("createdAt", user.getCreatedAt());
        data.put("updatedAt", user.getUpdatedAt());
        return R.ok(data);
    }

    @PostMapping("/updatePassword")
    public R<Void> updatePassword(@RequestBody ChangePasswordRequest request,
                                  HttpServletRequest httpServletRequest) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return R.fail(401, "未登录");
        }
        if (request == null
                || request.getOldPassword() == null
                || request.getNewPassword() == null
                || request.getConfirmPassword() == null) {
            return R.fail("请完整填写密码");
        }
        String newPassword = request.getNewPassword();
        if (newPassword.length() < 6 || newPassword.length() > 32) {
            return R.fail("新密码长度应为 6-32 个字符");
        }
        if (!newPassword.equals(request.getConfirmPassword())) {
            return R.fail("两次输入的新密码不一致");
        }
        if (newPassword.equals(request.getOldPassword())) {
            return R.fail("新密码不能与旧密码相同");
        }

        User user = userMapper.selectById(userId);
        if (user == null || !PasswordUtils.matches(request.getOldPassword(), user.getPasswordHash())) {
            return R.fail("旧密码不正确");
        }
        if (userMapper.updatePassword(userId, PasswordUtils.encode(newPassword)) != 1) {
            return R.fail("密码修改失败，请稍后重试");
        }

        String accessToken = httpServletRequest.getHeader("Authorization");
        authService.logout(accessToken, request.getRefreshToken());
        return R.ok(null, "密码修改成功，请重新登录");
    }

    /** 安全字符串转换 */
    private String str(Object v) {
        return v == null ? null : String.valueOf(v).trim();
    }

    private String randomAnswer() {
        StringBuilder builder = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            builder.append(CAPTCHA_CHARS.charAt(RANDOM.nextInt(CAPTCHA_CHARS.length())));
        }
        return builder.toString();
    }

    private String buildCaptchaSvg(String answer) {
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"120\" height=\"42\" viewBox=\"0 0 120 42\">")
                .append("<rect width=\"120\" height=\"42\" fill=\"#f8fafc\"/>");
        for (int i = 0; i < 3; i++) {
            int y = 8 + RANDOM.nextInt(30);
            int y2 = 8 + RANDOM.nextInt(30);
            svg.append("<line x1=\"0\" y1=\"").append(y)
                    .append("\" x2=\"120\" y2=\"").append(y2)
                    .append("\" stroke=\"#94a3b8\" stroke-width=\"1\" opacity=\".65\"/>");
        }
        for (int i = 0; i < answer.length(); i++) {
            int x = 15 + i * 25;
            int y = 29 + RANDOM.nextInt(5);
            int rotate = -25 + RANDOM.nextInt(51);
            String color = i % 2 == 0 ? "#1d4ed8" : "#be123c";
            svg.append("<text x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" fill=\"").append(color)
                    .append("\" font-size=\"24\" font-family=\"Arial,sans-serif\" font-weight=\"700\"")
                    .append(" transform=\"rotate(").append(rotate).append(" ")
                    .append(x).append(" ").append(y).append(")\">")
                    .append(answer.charAt(i)).append("</text>");
        }
        return svg.append("</svg>").toString();
    }
}
