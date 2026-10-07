package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * AI 角色表(AiAgent)实体类
 */
@Data
public class AiAgentEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;
    /** 创建者ID */
    private Long userid;
    /**
     * 关联的代码项目ID列表。角色与项目是多对多：一个角色可以覆盖多个项目
     * （例如前后端共用一个角色）。该字段不落在 "AiAgent" 表上，来自 ai_agent_project 关联表，
     * 由 Service 层填充。
     */
    private List<Long> projectIds;
    /** 角色名称 */
    private String name;
    /** 角色编码 */
    private String code;
    /** 模型标识（留空=系统默认） */
    private String model;
    /** 系统提示词 */
    private String systemprompt;
    /** 采样温度 */
    private BigDecimal temperature;
    /** 最大输出token */
    private Integer maxtokens;
    /** 是否启用 1启用 0停用 */
    private Integer enabled;
    /** 历史字段：1系统预置 0用户自建。当前所有角色均由项目创建，恒为 0。 */
    private Integer issystem;
    /** 头像URL或emoji */
    private String avatar;
    /** 厂商标识 deepseek/openai/qwen（留空=系统默认） */
    private String provider;
    /** 厂商base-url（留空=系统默认） */
    private String apibaseurl;
    /** 用户自带API Key（AES加密） */
    private String apikeyenc;
    /** 创建时间 */
    private Date createdat;
    /** 更新时间 */
    private Date updatedat;
}
