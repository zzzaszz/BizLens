package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 代码知识库项目元数据。 */
@Data
public class AiCodeProjectEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String gitUrl;
    private String gitBranch;
    private String gitCommit;
    /** 私有仓库访问令牌（AES 加密后的密文；对前端只回脱敏串） */
    private String credentialEnc;
    /** 令牌对应的用户名；留空时按 Git 平台取默认值 */
    private String credentialUser;
    private String status;
    /** 最近一次索引失败的原因（已截断、已抹掉 URL 中的凭据）；成功时为空 */
    private String lastError;
    private Date lastIndexedAt;
    /** 最近一次成功索引的耗时（毫秒），仅成功时写入；用于估算"还要多久" */
    private Long lastIndexMs;
    /**
     * 距今（查询时刻）已过去多久，单位毫秒，由 SQL 用**数据库时钟**算好返回。
     * <p>
     * 不交给前端用浏览器时钟算：updated_at 是数据库写的，客户端时钟与服务端往往不一致
     * （本机实测差 13 秒），直接相减会让"已用 13 秒"在刚开始时就出现。
     */
    private Long elapsedMs;
    private Integer documentCount;
    private Integer chunkCount;
    private Integer symbolChunkCount;
    /** 本次索引扫描到的可索引文件总数。 */
    private Integer indexTotalFiles;
    /** 本次索引已处理的文件数。 */
    private Integer indexProcessedFiles;
    /** 因超过大小、数量限制或单文件异常而跳过的文件数。 */
    private Integer indexSkippedFiles;
    /** 索引任务最近一次心跳距今的毫秒数，由数据库时钟计算。 */
    private Long heartbeatElapsedMs;
    /** 当前正在处理的文件相对路径。 */
    private String indexCurrentFile;
    private Long createdBy;
    private Date createdAt;
    private Date updatedAt;
}
