package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 代码知识库项目元数据，不保存源码内容。 */
@Data
public class AiCodeProjectEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String gitUrl;
    private String gitBranch;
    private String gitCommit;
    private String credentialRef;
    private String status;
    private Date lastIndexedAt;
    private Integer documentCount;
    private Integer chunkCount;
    private Integer symbolChunkCount;
    private Long createdBy;
    private Date createdAt;
    private Date updatedAt;
}
