package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 消息表(AiChatMessage)实体类
 */
@Data
public class AiChatMessageEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;
    /** 会话ID */
    private Long conversationid;
    /** 发送者类型 USER/AI/SYSTEM */
    private String sendertype;
    /** 发送者ID（用户ID或AI编码） */
    private String senderid;
    /** 消息内容 */
    private String content;
    /** 创建时间 */
    private Date createtime;
}
