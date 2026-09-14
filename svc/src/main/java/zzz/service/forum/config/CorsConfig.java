package zzz.service.forum.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import javax.annotation.Resource;
import java.util.Arrays;

/**
 * 跨域配置 - 兼容性实现
 * 规则：
 * 1. 如果注册进了Nacos，则不启用此配置，由Gateway统一处理跨域
 * 2. 如果未注册Nacos，则启用此配置处理跨域
 */
@Configuration
public class CorsConfig {

    private static final Logger logger = LoggerFactory.getLogger(CorsConfig.class);

    @Resource
    private Environment environment;

    /**
     * 条件化CORS过滤器
     * 只有在未启用Nacos服务发现时才生效
     * 通过配置 spring.cloud.nacos.discovery.enabled=false 来禁用Nacos注册
     */
    @Bean
    @ConditionalOnProperty(
            name = "spring.cloud.nacos.discovery.enabled",
            havingValue = "false"
    )
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        logger.info("=== CORS配置启用 ===");
        logger.info("检测到未注册Nacos服务发现，启用本地CORS跨域配置");

        CorsConfiguration config = new CorsConfiguration();

        // 情况1：仅使用 Authorization 头（推荐，安全），不需要 Cookie
        config.setAllowCredentials(false); // 带 * 时必须 false
        config.addAllowedOriginPattern("*"); // 放开所有来源

        // 如果未来需要支持 Cookie，可以改成以下（两选一）：
        // config.setAllowCredentials(true);
        // config.setAllowedOrigins(Arrays.asList("http://localhost:63344", "https://zjhz-oem.dm.inksyun.com"));

        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.addAllowedHeader("*");
        config.setMaxAge(1800L);

        // 关键：暴露文件下载相关的头部，前端 fetch 才能拿到文件名
        config.setExposedHeaders(Arrays.asList(
                "Content-Disposition", "Content-Length", "Content-Type"
        ));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        logger.info("CORS过滤器已注册，优先级：HIGHEST_PRECEDENCE");
        return bean;
    }

    /**
     * Nacos状态检查Bean - 用于日志输出
     */
    @Bean
    @ConditionalOnProperty(
            name = "spring.cloud.nacos.discovery.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public Object nacosStatusChecker() {
        logger.info("=== CORS配置跳过 ===");
        logger.info("检测到已注册Nacos服务发现，跳过本地CORS配置");
        logger.info("跨域处理将由Gateway统一管理");

        String serverAddr = environment.getProperty("spring.cloud.nacos.discovery.server-addr");
        String serviceName = environment.getProperty("spring.application.name");
        if (serverAddr != null && serviceName != null) {
            logger.info("Nacos服务器: {}, 服务名: {}", serverAddr, serviceName);
        }

        return new Object();
    }
}
