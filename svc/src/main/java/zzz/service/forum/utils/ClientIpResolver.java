package zzz.service.forum.utils;

import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 解析客户端 IP。
 *
 * <p>只有请求来自明确配置的可信反向代理时才读取 X-Forwarded-For，
 * 直接暴露服务端口时忽略客户端伪造的请求头。</p>
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request, String trustedProxyIps) {
        String remoteAddr = request.getRemoteAddr();
        Set<String> trusted = parseTrustedProxyIps(trustedProxyIps);
        if (trusted.contains(remoteAddr)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                String first = forwarded.split(",", 2)[0].trim();
                if (StringUtils.hasText(first)) {
                    return first;
                }
            }
        }
        return StringUtils.hasText(remoteAddr) ? remoteAddr : "unknown";
    }

    private static Set<String> parseTrustedProxyIps(String value) {
        if (!StringUtils.hasText(value)) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .forEach(result::add);
        return result;
    }
}
