package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class AiKnowledgeDocumentEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long sourceId;
    private String path;
    private String title;
    private String checksum;
    private Integer sizeBytes;
    private Integer chunkCount;
    private Date createdAt;
    private Date updatedAt;
}
