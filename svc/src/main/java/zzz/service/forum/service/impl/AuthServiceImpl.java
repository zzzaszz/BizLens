package zzz.service.forum.service.impl;

import com.alibaba.cloud.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zzz.service.forum.domain.User;
import zzz.service.forum.domain.dto.LoginRequest;
import zzz.service.forum.domain.dto.RefreshRequest;
import zzz.service.forum.domain.dto.RegisterRequest;
import zzz.service.forum.domain.vo.LoginResponse;
import zzz.service.forum.mapper.UserMapper;
import zzz.service.forum.service.AuthService;
import zzz.service.forum.service.InviteCodeService;
import zzz.service.forum.service.RedisService;
import zzz.service.forum.utils.AuthBusinessException;
import zzz.service.forum.utils.ClientIpResolver;
import zzz.service.forum.utils.JwtUtils;
import zzz.service.forum.utils.PasswordUtils;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String ACCESS_TOKEN_KEY_PREFIX = "login:access:";
    private static final String REFRESH_TOKEN_KEY_PREFIX = "login:refresh:";
    private static final String LOGIN_RATE_LIMIT_PREFIX = "login:limit:";
    private static final String REGISTER_IP_PREFIX = "register:ip:";
    private static final String LOGIN_FAIL_PREFIX = "login:fail:";
    private static final String CAPTCHA_PREFIX = "login:captcha:";

    // 同IP每分钟最多登录 10 次
    private static final int LOGIN_MAX_TIMES = 10;
    private static final long LOGIN_RATE_LIMIT_SECONDS = 60;

    // 同IP每天最多注册 5 个新账号
    private static final int IP_MAX_REGISTER = 5;
    private static final long REGISTER_LIMIT_SECONDS = 86400;

    // 账号：3-32 位字母数字下划线
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");
    private static final Pattern CAPTCHA_ID_PATTERN = Pattern.compile("^[A-Za-z0-9-]{16,64}$");

    @Resource
    private UserMapper userMapper;

    @Resource
    private RedisService redisService;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Resource
    private InviteCodeService inviteCodeService;

    @Value("${auth.invite-enabled:true}")
    private boolean inviteEnabled;

    @Value("${auth.captcha-enabled:true}")
    private boolean captchaEnabled;

    @Value("${auth.trusted-proxy-ips:}")
    private String trustedProxyIps;

    @Value("${auth.login-fail-threshold:5}")
    private int loginFailCaptchaThreshold;

    @Value("${auth.login-fail-ttl-seconds:2592000}")
    private long loginFailTtlSeconds;

    @Override
    public LoginResponse login(LoginRequest request, HttpServletRequest httpServletRequest) {
        String ip = getIp(httpServletRequest);

        // IP 登录频次限制
        String rateKey = LOGIN_RATE_LIMIT_PREFIX + ip;
        Long count = redisService.incrementWithInitialExpire(rateKey, 1, LOGIN_RATE_LIMIT_SECONDS);
        if (count == null) {
            throw new RuntimeException("服务暂时不可用，请稍后重试");
        }
        if (count > LOGIN_MAX_TIMES) {
            throw new RuntimeException("登录请求过于频繁，请稍后再试");
        }

        String username = request == null ? null : normalizeUsername(request.getUsername());
        String password = request == null ? null : request.getPassword();
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            throw new RuntimeException("账号或密码不能为空");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new RuntimeException("账号或密码错误");
        }

        User user = userMapper.getByUsername(username);

        String failKey = LOGIN_FAIL_PREFIX + username;
        long failCount = readLong(redisService.getCacheObject(failKey));
        if (captchaEnabled && failCount >= loginFailCaptchaThreshold) {
            String captchaId = request.getCaptchaId();
            String captchaCode = request.getCaptchaCode();
            boolean validCaptcha = CAPTCHA_ID_PATTERN.matcher(
                    captchaId == null ? "" : captchaId.trim()).matches()
                    && StringUtils.isNotBlank(captchaCode)
                    && redisService.consumeIfMatches(
                    CAPTCHA_PREFIX + captchaId.trim(),
                    captchaCode.trim().toUpperCase());
            if (!validCaptcha) {
                throw captchaException(failCount);
            }
        }

        // 账号不存在或密码不匹配，统一提示，避免账号枚举
        if (user == null || !PasswordUtils.matches(password, user.getPasswordHash())) {
            if (user != null) {
                redisService.incrementAndExpire(failKey, 1, loginFailTtlSeconds);
            }
            throw new RuntimeException("账号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 2) {
            throw new RuntimeException("账号已被封禁");
        }

        redisService.deleteObject(failKey);

        // 更新登录信息
        user.setLastLoginIp(ip);
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.update(user);

        return issueTokens(user);
    }

    @Override
    @Transactional
    public LoginResponse register(RegisterRequest request, HttpServletRequest httpServletRequest) {
        String ip = getIp(httpServletRequest);

        String username = request == null ? null : request.getUsername();
        String password = request == null ? null : request.getPassword();
        username = normalizeUsername(username);
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            throw new RuntimeException("账号或密码不能为空");
        }
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new RuntimeException("账号需为 3-32 位字母、数字或下划线");
        }
        if (password.length() < 6 || password.length() > 32) {
            throw new RuntimeException("密码长度应为 6-32 位");
        }

        // 用户名唯一性校验
        if (userMapper.getByUsername(username) != null) {
            throw new RuntimeException("该账号已被注册");
        }

        User user = new User();
        user.setId(snowflakeIdGenerator.nextId());
        // deviceId 未提供时用 username 占位，保证非空
        user.setDeviceId(StringUtils.isNotBlank(request.getDeviceId()) ? request.getDeviceId() : username);
        user.setUsername(username);
        user.setPasswordHash(PasswordUtils.encode(password));
        user.setNickname(StringUtils.isNotBlank(request.getNickname()) ? request.getNickname() : generateNickname(user.getId()));
        user.setAvatar("");
        user.setStatus(1);
        user.setRole(0); // 普通用户；管理员需手动在库中提权
        user.setDeleted(0);
        user.setLastLoginIp(ip);
        user.setLastLoginAt(LocalDateTime.now());

        if (inviteEnabled && userMapper.countUsers() > 0) {
            if (request == null || StringUtils.isBlank(request.getInviteCode())) {
                throw new RuntimeException("注册需要邀请码");
            }
            inviteCodeService.claim(request.getInviteCode(), user.getId());
        }

        // IP 注册限制：同 IP 每天最多 5 个
        String ipKey = REGISTER_IP_PREFIX + ip;
        Long ipCount = redisService.incrementWithInitialExpire(ipKey, 1, REGISTER_LIMIT_SECONDS);
        if (ipCount == null) {
            throw new RuntimeException("服务暂时不可用，请稍后重试");
        }
        if (ipCount > IP_MAX_REGISTER) {
            throw new RuntimeException("当前网络下注册次数已达上限，请明天再试");
        }

        if (userMapper.insert(user) != 1) {
            throw new RuntimeException("注册失败，请稍后重试");
        }

        return issueTokens(user);
    }

    @Override
    public LoginResponse refresh(RefreshRequest request) {
        String refreshToken = request.getRefreshToken();
        if (StringUtils.isBlank(refreshToken)) {
            throw new RuntimeException("refreshToken不能为空");
        }

        String tokenType;
        try {
            tokenType = JwtUtils.getTokenType(refreshToken);
        } catch (Exception e) {
            throw new RuntimeException("无效的RefreshToken");
        }
        if (!JwtUtils.TOKEN_TYPE_REFRESH.equals(tokenType)) {
            throw new RuntimeException("非法的RefreshToken");
        }

        Long userId;
        try {
            userId = JwtUtils.parseToken(refreshToken).get("userId", Long.class);
        } catch (Exception e) {
            throw new RuntimeException("RefreshToken解析失败");
        }

        // 校验 refresh token 是否在 Redis 中存在且与当前提供的一致
        String refreshKey = REFRESH_TOKEN_KEY_PREFIX + userId;
        String storedToken = redisService.getCacheObject(refreshKey);
        if (storedToken == null || !storedToken.equals(refreshToken)) {
            throw new RuntimeException("RefreshToken已过期");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        String newAccessToken = JwtUtils.generateAccessToken(userId, user.getDeviceId());
        // 刷新后必须更新 Redis 中的 access token，否则 AuthAspect 仍按旧值校验导致 401 死循环
        redisService.setCacheObject(ACCESS_TOKEN_KEY_PREFIX + userId, newAccessToken, 30L, TimeUnit.MINUTES);

        return LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken)
                .token(newAccessToken)
                .userId(userId)
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole() == null ? 0 : user.getRole())
                .themeColor(user.getThemeColor())
                .build();
    }

    @Override
    public void logout(String accessToken, String refreshToken) {
        if (StringUtils.isNotBlank(accessToken)) {
            try {
                Long userId = JwtUtils.parseToken(accessToken).get("userId", Long.class);
                redisService.deleteObject(ACCESS_TOKEN_KEY_PREFIX + userId);
            } catch (Exception ignored) {
            }
        }

        if (StringUtils.isNotBlank(refreshToken)) {
            try {
                Long userId = JwtUtils.parseToken(refreshToken).get("userId", Long.class);
                redisService.deleteObject(REFRESH_TOKEN_KEY_PREFIX + userId);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 生成并缓存 access/refresh token，组装登录响应
     */
    private LoginResponse issueTokens(User user) {
        Long userId = user.getId();
        String deviceId = user.getDeviceId();
        String accessToken = JwtUtils.generateAccessToken(userId, deviceId);
        String refreshToken = JwtUtils.generateRefreshToken(userId, deviceId);

        redisService.setCacheObject(ACCESS_TOKEN_KEY_PREFIX + userId, accessToken, 30L, TimeUnit.MINUTES);
        redisService.setCacheObject(REFRESH_TOKEN_KEY_PREFIX + userId, refreshToken, 30L, TimeUnit.DAYS);

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .token(accessToken)
                .userId(userId)
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole() == null ? 0 : user.getRole())
                .themeColor(user.getThemeColor())
                .build();
    }

    private String generateNickname(Long userId) {
        String[] names = {"匿名小猫", "吃瓜土豆", "熬夜企鹅", "摸鱼选手", "快乐网友"};
        String suffix = String.valueOf(userId);
        suffix = suffix.substring(Math.max(0, suffix.length() - 5));
        return names[new Random().nextInt(names.length)] + suffix;
    }

    private String getIp(HttpServletRequest request) {
        return ClientIpResolver.resolve(request, trustedProxyIps);
    }

    private String normalizeUsername(String username) {
        return username == null ? null : username.trim();
    }

    private long readLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private AuthBusinessException captchaException(long failCount) {
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("needCaptcha", true);
        data.put("failCount", failCount);
        return new AuthBusinessException(4001, "请输入验证码", data);
    }
}
