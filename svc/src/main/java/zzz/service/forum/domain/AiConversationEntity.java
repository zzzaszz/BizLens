package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * AI 会话表(AiConversation)实体类
 */
@Data
public class AiConversationEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 主键ID */
    private Long id;
    /** 发起用户ID */
    private Long userid;
    /** 关联AI角色ID（可空=通用AI） */
    private Long agentid;
    /** 会话标题 */
    private String title;
    /** 状态 0关闭 1进行中 */
    private Integer status;
    /** 创建时间 */
    private Date createdat;
    /** 更新时间 */
    private Date updatedat;
}
