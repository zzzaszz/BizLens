package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** Git Markdown personal knowledge source metadata. */
@Data
public class AiKnowledgeSourceEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String sourceType;
    private String gitUrl;
    private String gitBranch;
    private String gitCommit;
    private String rootPath;
    private String credentialEnc;
    private String credentialUser;
    private String status;
    private String lastError;
    private Date lastIndexedAt;
    private Long lastIndexMs;
    private Long elapsedMs;
    private Integer documentCount;
    private Integer chunkCount;
    private Integer indexTotalFiles;
    private Integer indexProcessedFiles;
    private Integer indexSkippedFiles;
    /** 索引任务最近一次心跳距今的毫秒数，由数据库时钟计算。 */
    private Long heartbeatElapsedMs;
    /** 当前正在处理的 Markdown 文件相对路径。 */
    private String indexCurrentFile;
    private Long createdBy;
    private Date createdAt;
    private Date updatedAt;
}
