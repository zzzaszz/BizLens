package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 创建 / 编辑 AI 角色请求
 */
@Data
public class AiAgentRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 角色ID（编辑时必传） */
    private Long id;
    /**
     * 关联的代码项目ID（至少一个）。角色与项目多对多：可以一次覆盖多个项目，
     * 例如前端和后端两个仓库共用一个角色。编辑时整体替换。
     */
    private List<Long> projectIds;
    /** 关联的个人知识库ID（可与代码项目同时选择，编辑时整体替换）。 */
    private List<Long> knowledgeIds;
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
    /** API Key（创建时必填；编辑时留空表示不覆盖。明文，后端加密后入库） */
    private String apiKey;
    /** 是否启用（编辑时可选） */
    private Integer enabled;
}
