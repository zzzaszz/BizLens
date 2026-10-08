package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AiKnowledgeChunkHit implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long chunkId;
    private Long documentId;
    private Long sourceId;
    private String sourceName;
    private String path;
    private String title;
    private String headingPath;
    private Integer headingLevel;
    private String content;
    private Integer startLine;
    private Integer endLine;
    private Integer score;
    private Double similarity;
}
