package zzz.service.forum.service.impl;

import com.alibaba.cloud.commons.lang.StringUtils;
import org.springframework.stereotype.Service;
import zzz.service.forum.domain.User;
import zzz.service.forum.domain.dto.LoginRequest;
import zzz.service.forum.domain.dto.RefreshRequest;
import zzz.service.forum.domain.dto.RegisterRequest;
import zzz.service.forum.domain.vo.LoginResponse;
import zzz.service.forum.mapper.UserMapper;
import zzz.service.forum.service.AuthService;
import zzz.service.forum.service.RedisService;
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

    // 同IP每分钟最多登录 10 次
    private static final int LOGIN_MAX_TIMES = 10;
    private static final long LOGIN_RATE_LIMIT_SECONDS = 60;

    // 同IP每天最多注册 5 个新账号
    private static final int IP_MAX_REGISTER = 5;
    private static final long REGISTER_LIMIT_SECONDS = 86400;

    // 账号：3-32 位字母数字下划线
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");

    @Resource
    private UserMapper userMapper;

    @Resource
    private RedisService redisService;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public LoginResponse login(LoginRequest request, HttpServletRequest httpServletRequest) {
        String ip = getIp(httpServletRequest);

        // IP 登录频次限制
        String rateKey = LOGIN_RATE_LIMIT_PREFIX + ip;
        Long count = redisService.increment(rateKey, 1);
        if (count == 1) {
            redisService.expire(rateKey, LOGIN_RATE_LIMIT_SECONDS);
        }
        if (count > LOGIN_MAX_TIMES) {
            throw new RuntimeException("登录请求过于频繁，请稍后再试");
        }

        String username = request.getUsername();
        String password = request.getPassword();
        if (StringUtils.isBlank(username) || StringUtils.isBlank(password)) {
            throw new RuntimeException("账号或密码不能为空");
        }

        User user = userMapper.getByUsername(username);
        // 账号不存在或密码不匹配，统一提示，避免账号枚举
        if (user == null || !PasswordUtils.matches(password, user.getPasswordHash())) {
            throw new RuntimeException("账号或密码错误");
        }
        if (user.getStatus() != null && user.getStatus() == 2) {
            throw new RuntimeException("账号已被封禁");
        }

        // 更新登录信息
        user.setLastLoginIp(ip);
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.update(user);

        return issueTokens(user);
    }

    @Override
    public LoginResponse register(RegisterRequest request, HttpServletRequest httpServletRequest) {
        String ip = getIp(httpServletRequest);

        String username = request == null ? null : request.getUsername();
        String password = request == null ? null : request.getPassword();
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

        // IP 注册限制：同 IP 每天最多 5 个
        String ipKey = REGISTER_IP_PREFIX + ip;
        Long ipCount = redisService.increment(ipKey, 1);
        if (ipCount == 1) {
            redisService.expire(ipKey, REGISTER_LIMIT_SECONDS);
        }
        if (ipCount > IP_MAX_REGISTER) {
            throw new RuntimeException("当前网络下注册次数已达上限，请明天再试");
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
        userMapper.insert(user);

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
        String ip = request.getHeader("X-Forwarded-For");
        if (StringUtils.isBlank(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
