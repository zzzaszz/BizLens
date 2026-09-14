package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/** 方法级/文件级切片元数据。源码和 embedding 均不在本阶段落库。 */
@Data
public class AiCodeChunkEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long documentId;
    private String chunkType;
    private String symbolName;
    private String businessSummary;
    private String metadata;
    private Date createdAt;
}
