package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 检索命中的代码切片。
 * <p>
 * 只用于把召回结果拼进模型上下文，不对外返回给前端——里面的 sourceSnippet 是源码原文。
 */
@Data
public class AiCodeChunkHit implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 文件相对路径 */
    private String path;
    private String language;
    /** 切片类型：FILE / CLASS / METHOD / MAPPER 等 */
    private String chunkType;
    private String symbolName;
    private String businessSummary;
    /** 源码原文（索引时已截断） */
    private String sourceSnippet;
    private Integer startLine;
    private Integer endLine;
    /** 命中权重，越大越相关 */
    private Integer score;
}
