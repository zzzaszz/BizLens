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

    /** 切片主键，用于合并普通召回与关系扩展结果时去重 */
    private Long chunkId;
    /** 切片所属文件文档 */
    private Long documentId;
    /** 切片所属代码项目 */
    private Long projectId;
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
    /** 向量相似度，越大越相关。 */
    private Double similarity;
    /** 是否由一跳继承关系补充 */
    private boolean inherited;
    /** 是否属于文件级完整检索结果；此类结果不受普通召回条数限制。 */
    private boolean completeFile;
    /** 调用链中的层级，种子节点为 0。 */
    private Integer relationDepth;
    /** 从当前节点向下追踪时使用的关系类型。 */
    private String relationType;
}
