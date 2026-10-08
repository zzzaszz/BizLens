package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

/** 索引阶段由源码引用推导出的代码关系。 */
@Data
public class AiCodeRelationCandidate implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long sourceChunkId;
    private Long targetChunkId;
    private String relationType;
}
