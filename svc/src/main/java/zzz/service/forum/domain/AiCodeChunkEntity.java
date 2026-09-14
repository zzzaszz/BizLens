package zzz.service.forum.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 方法级/文件级切片元数据。
 * <p>
 * source_snippet 落库的是该切片的源码原文（已按长度预算截断），供对话时召回注入模型理解业务逻辑；
 * 模型回答侧仍由 AiSafetyGuard 拦截源码输出，源码只进上下文、不出回答。
 * embedding 仍为空，本阶段不做向量化。
 */
@Data
public class AiCodeChunkEntity implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long documentId;
    private String chunkType;
    private String symbolName;
    private String businessSummary;
    private String metadata;
    /** 切片对应的源码原文（已截断），用于对话时的业务理解 */
    private String sourceSnippet;
    private Date createdAt;
}
