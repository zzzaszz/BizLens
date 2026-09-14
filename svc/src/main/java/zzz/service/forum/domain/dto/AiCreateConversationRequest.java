package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 创建 AI 会话请求
 */
@Data
public class AiCreateConversationRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 关联AI角色ID（可空=通用AI） */
    private Long agentId;
    /** 会话标题（可空，默认"AI 对话"） */
    private String title;
}
