package zzz.service.forum.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.service.RedisService;
import zzz.service.forum.utils.JwtUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 认证切面：对所有 Controller 接口统一校验 Token 是否到期。
 * <p>
 * Token 过期或无效时直接返回 401 "登录到期"，不进入业务逻辑。
 *
 * @author caoshi
 * @since 2026-07-09
 */
@Aspect
@Component
public class AuthAspect {

    private static final Logger log = LoggerFactory.getLogger(AuthAspect.class);

    private static final String ACCESS_TOKEN_KEY_PREFIX = "login:access:";

    /** 无需 Token 校验的接口路径 */
    private static final Set<String> SKIP_PATHS = new HashSet<>(Arrays.asList(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/system/health"
    ));

    /** Swagger/Knife4j 等无需拦截 */
    private static final Set<String> SKIP_PREFIXES = new HashSet<>(Arrays.asList(
            "/swagger", "/webjars", "/v2/api-docs", "/v3/api-docs", "/doc.html", "/favicon", "/error"
    ));

    @Resource
    private RedisService redisService;

    /** 切点：所有 controller 包下的 public 方法 */
    @Pointcut("execution(public * zzz.service.forum.controller..*.*(..))")
    public void controllerLayer() {
    }

    @Around("controllerLayer()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return joinPoint.proceed();
        }

        HttpServletRequest request = attrs.getRequest();
        String path = request.getRequestURI();

        // 跳过不需要校验的路径
        if (SKIP_PATHS.contains(path)) {
            return joinPoint.proceed();
        }
        for (String prefix : SKIP_PREFIXES) {
            if (path.startsWith(prefix)) {
                return joinPoint.proceed();
            }
        }

        // 校验 Token
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            log.debug("缺少 Authorization 头，path={}", path);
            return R.fail(401, "登录到期");
        }

        String token = authHeader;
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        try {
            // 必须是 access token
            if (!JwtUtils.isAccessToken(token)) {
                log.debug("非 access token，path={}", path);
                return R.fail(401, "登录到期");
            }

            Long userId = JwtUtils.parseToken(token).get("userId", Long.class);

            // Redis 中校验 token 是否存在且一致
            String redisKey = ACCESS_TOKEN_KEY_PREFIX + userId;
            String storedToken = redisService.getCacheObject(redisKey);
            if (storedToken == null || !storedToken.equals(token)) {
                log.debug("Token 已过期或不存在，userId={}, path={}", userId, path);
                return R.fail(401, "登录到期");
            }

            // 设置用户上下文
            UserContext.setUserId(userId);
        } catch (Exception e) {
            log.debug("Token 校验异常，path={}, msg={}", path, e.getMessage());
            return R.fail(401, "登录到期");
        }

        // Token 有效，继续执行
        try {
            return joinPoint.proceed();
        } finally {
            UserContext.clear();
        }
    }
}
