package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import zzz.service.forum.domain.dto.AiCodeChunkHit;
import zzz.service.forum.mapper.AiCodeChunkMapper;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

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
    /** 单条切片注入 prompt 的字符上限，防止一条巨型方法把上下文吃光 */
    private static final int MAX_SNIPPET_IN_PROMPT = 1500;
    /** 注入 prompt 的总字符预算 */
    private static final int MAX_TOTAL_CHARS = 8000;

    @Resource
    private AiCodeChunkMapper chunkMapper;
    @Resource
    private AiQueryRewriter queryRewriter;

    /**
     * 检索与问题相关的代码切片。
     *
     * @param projectId 限定在该代码项目内检索
     * @param question  用户原始问题
     * @param model     角色模型（可空）
     * @param apiKey    角色 API Key
     * @param baseUrl   厂商 base-url（可空）
     * @param provider  厂商标识（可空）
     * @return 命中片段，按相关度排序；无命中或出错时返回空列表，不阻断对话
     */
    public List<AiCodeChunkHit> retrieve(Long projectId, String question, String model,
                                         String apiKey, String baseUrl, String provider) {
        if (projectId == null || question == null || question.trim().isEmpty()) {
            return Collections.emptyList();
        }
        try {
            List<String> keywords = queryRewriter.rewrite(question, model, apiKey, baseUrl, provider);
            if (keywords.isEmpty()) {
                log.info("未得到可用检索词，跳过知识库召回: projectId={}", projectId);
                return Collections.emptyList();
            }
            List<AiCodeChunkHit> hits = chunkMapper.searchByKeywords(projectId, keywords, MAX_HITS);
            log.info("知识库召回完成: projectId={}, keywords={}, hits={}",
                    projectId, keywords.size(), hits == null ? 0 : hits.size());
            return hits == null ? Collections.emptyList() : hits;
        } catch (Exception e) {
            // 召回失败不该挡住建对话：退化成没有知识库的普通问答，比直接报错好
            log.warn("知识库召回失败，本轮按无知识库处理: projectId={}, err={}", projectId, e.getMessage());
            return Collections.emptyList();
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
            if (hit.getStartLine() != null && hit.getStartLine() > 0) {
                context.append(" 第 ").append(hit.getStartLine()).append('-').append(hit.getEndLine()).append(" 行");
            }
            context.append(" ---\n").append(snippet).append('\n');
        }
        return context.length() == 0 ? null : context.toString();
    }
}
