package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** AI 请求安全审计记录；不保存 API Key、源码正文或模型原始响应。 */
@Data
public class AiSecurityAuditEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long userId;
    private Long projectId;
    private String question;
    private String retrievalMeta;
    private String model;
    private String responseStatus;
    private String triggeredRules;
    private Boolean blocked;
    private Date createdAt;
}
