package zzz.service.forum.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.service.RedisService;
import zzz.service.forum.utils.JwtUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
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
            return unauthorized(joinPoint);
        }

        String token = authHeader;
        if (token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        try {
            // 必须是 access token
            if (!JwtUtils.isAccessToken(token)) {
                log.debug("非 access token，path={}", path);
                return unauthorized(joinPoint);
            }

            Long userId = JwtUtils.parseToken(token).get("userId", Long.class);

            // Redis 中校验 token 是否存在且一致
            String redisKey = ACCESS_TOKEN_KEY_PREFIX + userId;
            String storedToken = redisService.getCacheObject(redisKey);
            if (storedToken == null || !storedToken.equals(token)) {
                log.debug("Token 已过期或不存在，userId={}, path={}", userId, path);
                return unauthorized(joinPoint);
            }

            // 设置用户上下文
            UserContext.setUserId(userId);
        } catch (Exception e) {
            log.debug("Token 校验异常，path={}, msg={}", path, e.getMessage());
            return unauthorized(joinPoint);
        }

        // Token 有效，继续执行
        try {
            return joinPoint.proceed();
        } finally {
            UserContext.clear();
        }
    }

    /**
     * 未通过校验时的返回值。
     * <p>
     * 普通接口返回 R 即可；但返回类型声明为 {@link SseEmitter} 的接口（对话流）不行——
     * Spring 按方法声明类型选返回值处理器，把 R 交给 SSE 处理器会直接类型不匹配，
     * 对外表现为空 body 的 HTTP 500，前端只能显示一句"请求失败"。
     * 这类接口改用同样带回 error 事件的 SseEmitter。
     */
    private Object unauthorized(ProceedingJoinPoint joinPoint) {
        if (!isSseMethod(joinPoint)) {
            return R.fail(401, "登录到期");
        }
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("error").data("登录已过期，请重新登录"));
            emitter.complete();
        } catch (Exception ignored) {
            // 推不出去也只能这样了，客户端会按流结束处理
        }
        return emitter;
    }

    private boolean isSseMethod(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        return SseEmitter.class.isAssignableFrom(method.getReturnType());
    }
}
