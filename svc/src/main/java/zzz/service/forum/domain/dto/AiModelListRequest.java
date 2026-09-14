package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 拉取厂商可用模型列表请求。
 * <p>
 * 仅用于填充「模型」下拉选项，Key / base-url 用完即弃，不落库、不打日志。
 */
@Data
public class AiModelListRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 厂商标识 deepseek/openai/qwen/custom */
    private String provider;
    /** 厂商 base-url（留空时按厂商取默认） */
    private String apiBaseUrl;
    /** API Key（新建角色时由表单传入明文；留空则改用 agentId 对应的已存 Key） */
    private String apiKey;
    /** 编辑已有角色时传，用于取库里已存的 Key */
    private Long agentId;
}
