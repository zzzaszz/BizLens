package zzz.service.forum.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 提供启动探针和最小系统状态信息。 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final JdbcTemplate jdbcTemplate;

    @Value("${spring.application.name:bizlens-ai}")
    private String applicationName;

    public SystemController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("service", applicationName);
        result.put("timestamp", OffsetDateTime.now().toString());
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            result.put("database", "UP");
        } catch (Exception e) {
            result.put("database", "DOWN");
            result.put("status", "DEGRADED");
        }
        return result;
    }
}
