package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 发送 AI 聊天消息请求
 */
@Data
public class AiSendRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 会话ID */
    private Long conversationId;
    /** 用户消息内容 */
    private String message;
}
