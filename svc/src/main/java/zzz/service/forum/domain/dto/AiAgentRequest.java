package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 创建 / 编辑 AI 角色请求
 */
@Data
public class AiAgentRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 角色ID（编辑时必传） */
    private Long id;
    /** 角色名称 */
    private String name;
    /** 角色编码（可空） */
    private String code;
    /** 模型标识（留空=系统默认） */
    private String model;
    /** 系统提示词 */
    private String systemPrompt;
    /** 采样温度 */
    private BigDecimal temperature;
    /** 最大输出token */
    private Integer maxTokens;
    /** 头像URL或emoji */
    private String avatar;
    /** 厂商标识 deepseek/openai/qwen（留空=系统默认） */
    private String provider;
    /** 厂商base-url（留空=系统默认） */
    private String apiBaseUrl;
    /** 用户自己的API Key（留空不覆盖；明文，后端加密后入库） */
    private String apiKey;
    /** 是否启用（编辑时可选） */
    private Integer enabled;
}
