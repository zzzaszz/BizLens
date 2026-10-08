package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class AiKnowledgeChunkEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long documentId;
    private Integer chunkIndex;
    private String headingPath;
    private Integer headingLevel;
    private String content;
    private String embeddingVector;
    private String metadata;
    private Date createdAt;
}
