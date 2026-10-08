package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import zzz.service.forum.ai.AiEmbeddingProvider;
import zzz.service.forum.ai.EmbeddingVectorUtils;
import zzz.service.forum.domain.dto.AiCodeChunkHit;
import zzz.service.forum.mapper.AiCodeChunkMapper;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码知识库召回：把用户问题转成候选标识符，再在项目内按关键词检索切片。
 * <p>
 * 只负责"拿到片段"，不负责"拼进模型上下文"——那部分在 AiServiceImpl 里。
 */
@Component
public class AiCodeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(AiCodeRetrievalService.class);

    /** 召回条数上限：条数越多 prompt 越长，成本与延迟都会上去 */
    private static final int MAX_HITS = 6;
    /** 一跳继承扩展最多补充的父类方法数，给普通关键词召回保留大部分名额 */
    private static final int MAX_INHERITED_HITS = 2;
    /** 一个问题最多解析两个父类文件，避免异常源码造成额外查询 */
    private static final int MAX_INHERITED_PARENTS = 2;
    /** 单条切片注入 prompt 的字符上限，防止一条巨型方法把上下文吃光 */
    private static final int MAX_SNIPPET_IN_PROMPT = 1500;
    /** 注入 prompt 的总字符预算 */
    private static final int MAX_TOTAL_CHARS = 8000;
    private static final int MAX_FILE_RESULTS = 8;
    private static final int MAX_CHAIN_DEPTH = 4;
    private static final int MAX_CHAIN_HITS = 16;

    /** Java 的 class/interface extends 声明；只从 FILE 切片中解析，避免命中方法正文里的普通文本。 */
    private static final Pattern JAVA_EXTENDS = Pattern.compile(
            "(?m)^[ \\t]*(?:[A-Za-z_$][\\w$]*\\s+)*(?:class|interface)\\s+"
                    + "[A-Za-z_$][\\w$]*(?:\\s*<[^>{}]*>)?\\s+extends\\s+"
                    + "(?:[A-Za-z_$][\\w$]*\\.)*([A-Za-z_$][\\w$]*)");

    @Resource
    private AiCodeChunkMapper chunkMapper;
    @Resource
    private AiQueryRewriter queryRewriter;
    @Resource
    private AiEmbeddingProvider embeddingProvider;

    /**
     * 检索与问题相关的代码切片。
     *
     * @param projectIds 限定在这些代码项目内检索（角色可关联多个项目，如前后端共用一个角色）
     * @param question   用户原始问题
     * @param model      角色模型（可空）
     * @param apiKey     角色 API Key
     * @param baseUrl    厂商 base-url（可空）
     * @param provider   厂商标识（可空）
     * @return 命中片段，按相关度排序；无命中或出错时返回空列表，不阻断对话
     */
    public List<AiCodeChunkHit> retrieve(List<Long> projectIds, String question, String model,
                                         String apiKey, String baseUrl, String provider) {
        if (projectIds == null || projectIds.isEmpty() || question == null || question.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<String> keywords = queryRewriter.rewrite(question, model, apiKey, baseUrl, provider);
            if (isFileOverviewQuestion(question)) {
                keywords = mergeExplicitKeywords(question, keywords);
            }
            if (keywords.isEmpty()) {
                log.info("未得到可用检索词，跳过知识库召回: projectIds={}", projectIds);
                return Collections.emptyList();
            }
            if (isFileOverviewQuestion(question)) {
                List<AiCodeChunkHit> complete = retrieveCompleteFiles(projectIds, keywords);
                if (!complete.isEmpty()) {
                    log.info("文件级完整检索完成: projectIds={}, files={}, symbols={}",
                            projectIds, countFiles(complete), complete.size());
                    return complete;
                }
            }
            List<AiCodeChunkHit> keywordHits = chunkMapper.searchByKeywords(projectIds, keywords, MAX_HITS);
            List<AiCodeChunkHit> vectorHits = searchByVector(projectIds, question);
            List<AiCodeChunkHit> hits = mergeUnique(vectorHits, keywordHits, MAX_HITS);
            hits = expandOneHopInheritance(hits, keywords);
            if (isCallChainQuestion(question) && hits != null && !hits.isEmpty()) {
                List<Long> seeds = new ArrayList<>();
                List<Long> documentIds = new ArrayList<>();
                for (AiCodeChunkHit hit : hits) {
                    if (hit.getChunkId() != null && !"FILE".equalsIgnoreCase(hit.getChunkType())) {
                        seeds.add(hit.getChunkId());
                    }
                    if ("FILE".equalsIgnoreCase(hit.getChunkType()) && hit.getDocumentId() != null) {
                        documentIds.add(hit.getDocumentId());
                    }
                }
                // 文件命中通常比方法名命中更稳定；把目标文件全部方法纳入链路种子，
                // 否则“Controller 调用链”可能只拿到 FILE 节点而没有可追踪的起点。
                if (!documentIds.isEmpty()) {
                    List<AiCodeChunkHit> symbols = chunkMapper.listSymbolsByDocuments(documentIds);
                    for (AiCodeChunkHit symbol : symbols) {
                        if (symbol.getChunkId() != null && !"TABLE".equalsIgnoreCase(symbol.getChunkType())) {
                            seeds.add(symbol.getChunkId());
                        }
                    }
                }
                seeds = uniqueIds(seeds);
                if (!seeds.isEmpty()) {
                    List<AiCodeChunkHit> chain = chunkMapper.findCallChain(seeds, MAX_CHAIN_DEPTH, MAX_CHAIN_HITS);
                    if (chain != null) {
                        hits = mergeUnique(hits, chain, MAX_CHAIN_HITS);
                    }
                }
            }
            log.info("知识库召回完成: projectIds={}, keywords={}, vectorHits={}, hits={}",
                    projectIds, keywords.size(), vectorHits.size(), hits == null ? 0 : hits.size());
            return hits == null ? Collections.emptyList() : hits;
        } catch (Exception e) {
            // 召回失败不该挡住建对话：退化成没有知识库的普通问答，比直接报错好
            log.warn("知识库召回失败，本轮按无知识库处理: projectIds={}, err={}", projectIds, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<AiCodeChunkHit> searchByVector(List<Long> projectIds, String question) {
        if (embeddingProvider == null || !embeddingProvider.isEnabled()) {
            return Collections.emptyList();
        }
        try {
            String vector = EmbeddingVectorUtils.toPgVectorLiteral(
                    embeddingProvider.embed(question), embeddingProvider.dimension());
            if (vector == null) return Collections.emptyList();
            List<AiCodeChunkHit> hits = chunkMapper.searchByVector(projectIds, vector, MAX_HITS);
            return hits == null ? Collections.<AiCodeChunkHit>emptyList() : hits;
        } catch (Exception e) {
            log.warn("向量召回失败，回退关键词召回: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<AiCodeChunkHit> retrieveCompleteFiles(List<Long> projectIds, List<String> keywords) {
        List<AiCodeChunkHit> files = chunkMapper.searchFiles(projectIds, keywords, MAX_FILE_RESULTS);
        if (files == null || files.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> documentIds = new ArrayList<>();
        for (AiCodeChunkHit file : files) {
            if (file.getDocumentId() != null) {
                documentIds.add(file.getDocumentId());
            }
        }
        List<AiCodeChunkHit> symbols = documentIds.isEmpty()
                ? Collections.<AiCodeChunkHit>emptyList() : chunkMapper.listSymbolsByDocuments(documentIds);
        Map<Long, AiCodeChunkHit> fileByDocument = new LinkedHashMap<>();
        for (AiCodeChunkHit file : files) {
            file.setCompleteFile(true);
            fileByDocument.put(file.getDocumentId(), file);
        }
        List<AiCodeChunkHit> result = new ArrayList<>();
        result.addAll(files);
        for (AiCodeChunkHit symbol : symbols) {
            AiCodeChunkHit file = fileByDocument.get(symbol.getDocumentId());
            if (file != null) {
                symbol.setCompleteFile(true);
                result.add(symbol);
            }
        }
        return result;
    }

    private List<String> mergeExplicitKeywords(String question, List<String> original) {
        Set<String> values = new LinkedHashSet<>();
        if (original != null) values.addAll(original);
        Matcher matcher = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$./-]{2,}").matcher(question);
        while (matcher.find() && values.size() < 16) {
            String value = matcher.group();
            if (!isCommonFileWord(value)) values.add(value);
        }
        return new ArrayList<>(values);
    }

    private boolean isCommonFileWord(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return "controller".equals(lower) || "service".equals(lower) || "mapper".equals(lower)
                || "file".equals(lower) || "method".equals(lower) || "methods".equals(lower)
                || "api".equals(lower) || "apis".equals(lower);
    }

    private List<Long> uniqueIds(List<Long> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private int countFiles(List<AiCodeChunkHit> hits) {
        int count = 0;
        for (AiCodeChunkHit hit : hits) {
            if (hit != null && "FILE".equalsIgnoreCase(hit.getChunkType())) count++;
        }
        return count;
    }

    private boolean isFileOverviewQuestion(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return q.contains("全部接口") || q.contains("所有接口") || q.contains("全部方法")
                || q.contains("所有方法") || q.contains("有哪些接口") || q.contains("有哪些方法")
                || q.contains("列出") && (q.contains("controller") || q.contains("文件"))
                || q.contains("list all") || q.contains("all methods") || q.contains("all apis");
    }

    private boolean isCallChainQuestion(String question) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return q.contains("调用链") || q.contains("调用关系") || q.contains("怎么调用")
                || q.contains("向下追踪") || q.contains("call chain") || q.contains("call graph")
                || q.contains("controller") && q.contains("mapper");
    }

    private List<AiCodeChunkHit> mergeUnique(List<AiCodeChunkHit> first,
                                             List<AiCodeChunkHit> second, int limit) {
        List<AiCodeChunkHit> merged = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (AiCodeChunkHit hit : first) {
            if (hit != null && keys.add(hitKey(hit))) merged.add(hit);
        }
        for (AiCodeChunkHit hit : second) {
            if (hit != null && keys.add(hitKey(hit))) merged.add(hit);
            if (merged.size() >= limit) break;
        }
        return merged;
    }

    /**
     * 对普通召回中的 FILE 切片做一次父类扩展。
     * <p>
     * 子类文件通常只说明"继承自谁"，而权限注解、通用接口实现等实际规则在父类方法上。
     * 这里最多向上走一跳，且只补充少量方法切片；不把检索变成递归图遍历，也不让扩展结果
     * 无限增加 prompt 长度。
     */
    private List<AiCodeChunkHit> expandOneHopInheritance(List<AiCodeChunkHit> baseHits,
                                                          List<String> keywords) {
        if (baseHits == null || baseHits.isEmpty() || keywords == null || keywords.isEmpty()) {
            return baseHits == null ? Collections.<AiCodeChunkHit>emptyList() : baseHits;
        }

        Set<ParentRef> parents = new LinkedHashSet<>();
        for (AiCodeChunkHit hit : baseHits) {
            if (parents.size() >= MAX_INHERITED_PARENTS
                    || hit == null
                    || hit.getProjectId() == null
                    || !"FILE".equalsIgnoreCase(hit.getChunkType())
                    || hit.getSourceSnippet() == null) {
                continue;
            }
            Matcher matcher = JAVA_EXTENDS.matcher(hit.getSourceSnippet());
            if (matcher.find()) {
                parents.add(new ParentRef(hit.getProjectId(), matcher.group(1)));
            }
        }
        if (parents.isEmpty()) {
            return baseHits;
        }

        Set<String> baseKeys = new HashSet<>();
        for (AiCodeChunkHit hit : baseHits) {
            baseKeys.add(hitKey(hit));
        }
        List<AiCodeChunkHit> inheritedHits = new ArrayList<>();
        for (ParentRef parent : parents) {
            if (inheritedHits.size() >= MAX_INHERITED_HITS) {
                break;
            }
            int remaining = MAX_INHERITED_HITS - inheritedHits.size();
            List<AiCodeChunkHit> found = chunkMapper.searchInheritedMethods(
                    parent.projectId, parent.className, keywords, remaining);
            if (found == null) {
                continue;
            }
            for (AiCodeChunkHit hit : found) {
                if (hit == null || !baseKeys.add(hitKey(hit))) {
                    continue;
                }
                hit.setInherited(true);
                inheritedHits.add(hit);
                if (inheritedHits.size() >= MAX_INHERITED_HITS) {
                    break;
                }
            }
        }
        if (inheritedHits.isEmpty()) {
            return baseHits;
        }

        List<AiCodeChunkHit> merged = new ArrayList<>(MAX_HITS);
        int originalLimit = MAX_HITS - inheritedHits.size();
        for (AiCodeChunkHit hit : baseHits) {
            if (merged.size() >= originalLimit) {
                break;
            }
            merged.add(hit);
        }
        merged.addAll(inheritedHits);
        return merged;
    }

    private String hitKey(AiCodeChunkHit hit) {
        if (hit == null) {
            return "null";
        }
        if (hit.getChunkId() != null) {
            return "chunk:" + hit.getChunkId();
        }
        return "fallback:" + hit.getProjectId() + ':' + hit.getDocumentId() + ':'
                + hit.getPath() + ':' + hit.getChunkType() + ':' + hit.getSymbolName();
    }

    private static final class ParentRef {
        private final Long projectId;
        private final String className;

        private ParentRef(Long projectId, String className) {
            this.projectId = projectId;
            this.className = className;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ParentRef)) {
                return false;
            }
            ParentRef that = (ParentRef) other;
            return projectId.equals(that.projectId) && className.equalsIgnoreCase(that.className);
        }

        @Override
        public int hashCode() {
            return 31 * projectId.hashCode() + className.toLowerCase(Locale.ROOT).hashCode();
        }
    }

    /**
     * 把召回结果拼成注入模型的上下文文本。
     *
     * @return 拼好的文本；无命中时返回 null（调用方据此决定是否加这段 system 消息）
     */
    public String buildContext(List<AiCodeChunkHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        StringBuilder context = new StringBuilder(1024);
        Set<Long> completeDocuments = new HashSet<>();
        StringBuilder completeFileIndex = new StringBuilder();
        boolean sourceBudgetNoticeAdded = false;
        for (AiCodeChunkHit hit : hits) {
            if (hit != null && hit.isCompleteFile() && "FILE".equalsIgnoreCase(hit.getChunkType())
                    && hit.getDocumentId() != null && completeDocuments.add(hit.getDocumentId())) {
                context.append("\n--- 完整文件目录 ").append(hit.getPath()).append(" ---\n");
                context.append("文件范围：第 ").append(hit.getStartLine()).append('-').append(hit.getEndLine()).append(" 行\n");
                continue;
            }
            if (hit.isCompleteFile() && !"FILE".equalsIgnoreCase(hit.getChunkType())
                    && hit.getSymbolName() != null) {
                completeFileIndex.append("- ").append(hit.getPath()).append(" :: ")
                        .append(hit.getChunkType()).append(' ').append(hit.getSymbolName())
                        .append("（第 ").append(hit.getStartLine()).append('-').append(hit.getEndLine()).append(" 行）\n");
            }
            String snippet = hit.getSourceSnippet();
            if (snippet == null || snippet.trim().isEmpty()) {
                continue;
            }
            if (snippet.length() > MAX_SNIPPET_IN_PROMPT) {
                snippet = snippet.substring(0, MAX_SNIPPET_IN_PROMPT) + "\n...(已截断)";
            }
            if (context.length() + snippet.length() > MAX_TOTAL_CHARS) {
                if (!sourceBudgetNoticeAdded) {
                    context.append("\n（上下文已达长度上限，源码片段省略；完整文件目录仍保留）\n");
                    sourceBudgetNoticeAdded = true;
                }
                continue;
            }
            context.append("\n--- ").append(hit.getPath());
            if (hit.getSymbolName() != null && !hit.getSymbolName().isEmpty()) {
                context.append(" [").append(hit.getChunkType()).append(' ').append(hit.getSymbolName()).append(']');
            }
            if (hit.isInherited()) {
                context.append("（一跳继承扩展）");
            }
            if (hit.getRelationDepth() != null) {
                context.append("（调用链第 ").append(hit.getRelationDepth()).append(" 层");
                if (hit.getRelationType() != null) context.append(' ').append(hit.getRelationType());
                context.append('）');
            }
            if (hit.getStartLine() != null && hit.getStartLine() > 0) {
                context.append(" 第 ").append(hit.getStartLine()).append('-').append(hit.getEndLine()).append(" 行");
            }
            context.append(" ---\n").append(snippet).append('\n');
        }
        if (!completeDocuments.isEmpty() && completeFileIndex.length() > 0) {
            context.append("\n完整文件中的全部已识别接口/方法/操作：\n").append(completeFileIndex);
        }
        return context.length() == 0 ? null : context.toString();
    }
}
