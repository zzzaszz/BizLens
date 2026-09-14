package zzz.service.forum.filter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.service.RedisService;
import zzz.service.forum.utils.JwtUtils;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final String ACCESS_TOKEN_KEY_PREFIX = "login:access:";

    private static final Set<String> SKIP_PATHS = new HashSet<>(Arrays.asList(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/logout"
    ));

    @Autowired
    private RedisService redisService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (SKIP_PATHS.contains(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 尝试解析 Token 并设置 UserContext（不做 401 拦截，由 AuthAspect AOP 统一处理）
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.isEmpty()) {
            String token = authHeader;
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            try {
                if (JwtUtils.isAccessToken(token)) {
                    Long userId = JwtUtils.parseToken(token).get("userId", Long.class);
                    String redisKey = ACCESS_TOKEN_KEY_PREFIX + userId;
                    String storedToken = redisService.getCacheObject(redisKey);
                    if (storedToken != null && storedToken.equals(token)) {
                        UserContext.setUserId(userId);
                    }
                }
            } catch (Exception ignored) {
                // Token 无效时由 AuthAspect 返回 401
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }
}
