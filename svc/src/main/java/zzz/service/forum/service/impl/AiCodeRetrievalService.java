package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import zzz.service.forum.domain.dto.AiCodeChunkHit;
import zzz.service.forum.mapper.AiCodeChunkMapper;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    /** Java 的 class/interface extends 声明；只从 FILE 切片中解析，避免命中方法正文里的普通文本。 */
    private static final Pattern JAVA_EXTENDS = Pattern.compile(
            "(?m)^[ \\t]*(?:[A-Za-z_$][\\w$]*\\s+)*(?:class|interface)\\s+"
                    + "[A-Za-z_$][\\w$]*(?:\\s*<[^>{}]*>)?\\s+extends\\s+"
                    + "(?:[A-Za-z_$][\\w$]*\\.)*([A-Za-z_$][\\w$]*)");

    @Resource
    private AiCodeChunkMapper chunkMapper;
    @Resource
    private AiQueryRewriter queryRewriter;

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
            if (keywords.isEmpty()) {
                log.info("未得到可用检索词，跳过知识库召回: projectIds={}", projectIds);
                return Collections.emptyList();
            }
            List<AiCodeChunkHit> hits = chunkMapper.searchByKeywords(projectIds, keywords, MAX_HITS);
            hits = expandOneHopInheritance(hits, keywords);
            log.info("知识库召回完成: projectIds={}, keywords={}, hits={}",
                    projectIds, keywords.size(), hits == null ? 0 : hits.size());
            return hits == null ? Collections.emptyList() : hits;
        } catch (Exception e) {
            // 召回失败不该挡住建对话：退化成没有知识库的普通问答，比直接报错好
            log.warn("知识库召回失败，本轮按无知识库处理: projectIds={}, err={}", projectIds, e.getMessage());
            return Collections.emptyList();
        }
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
        for (AiCodeChunkHit hit : hits) {
            String snippet = hit.getSourceSnippet();
            if (snippet == null || snippet.trim().isEmpty()) {
                continue;
            }
            if (snippet.length() > MAX_SNIPPET_IN_PROMPT) {
                snippet = snippet.substring(0, MAX_SNIPPET_IN_PROMPT) + "\n...(已截断)";
            }
            if (context.length() + snippet.length() > MAX_TOTAL_CHARS) {
                context.append("\n（上下文已达长度上限，其余片段省略）\n");
                break;
            }
            context.append("\n--- ").append(hit.getPath());
            if (hit.getSymbolName() != null && !hit.getSymbolName().isEmpty()) {
                context.append(" [").append(hit.getChunkType()).append(' ').append(hit.getSymbolName()).append(']');
            }
            if (hit.isInherited()) {
                context.append("（一跳继承扩展）");
            }
            if (hit.getStartLine() != null && hit.getStartLine() > 0) {
                context.append(" 第 ").append(hit.getStartLine()).append('-').append(hit.getEndLine()).append(" 行");
            }
            context.append(" ---\n").append(snippet).append('\n');
        }
        return context.length() == 0 ? null : context.toString();
    }
}
