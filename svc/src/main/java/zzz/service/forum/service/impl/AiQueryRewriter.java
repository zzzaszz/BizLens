package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import zzz.service.forum.ai.AiMessage;
import zzz.service.forum.ai.AiProvider;
import zzz.service.forum.ai.AiRequest;
import zzz.service.forum.ai.AiResponse;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把中文业务问题改写成代码库里可能出现的候选标识符，供关键词召回使用。
 * <p>
 * 为什么需要这一步：库里没有向量（embedding 列为空，DeepSeek 也不提供 embedding 接口），
 * 而用户问的是"订单超时怎么处理的"、库里存的却是 {@code OrderService#handleOrderTimeout}——
 * 朴素关键词匹配一个都命中不了。所以先用一次低温度模型调用把问题"翻译"成检索词。
 * <p>
 * 代价：每条消息多一次模型调用（几百 token）。失败时回退到问题里的英文 token，不阻断对话。
 */
@Component
public class AiQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(AiQueryRewriter.class);

    /** 改写调用的温度：要的是稳定的词表，不是创造力 */
    private static final double TEMPERATURE = 0.1d;
    /** 最多取多少个关键词——每个词都要在全表上做 3 次 ILIKE，词太多会拖慢检索 */
    private static final int MAX_KEYWORDS = 8;
    /** 过短的词（如 a、of）没有区分度，反而制造噪声 */
    private static final int MIN_KEYWORD_LENGTH = 3;
    /** 关键词允许的字符：标识符、路径片段、表名常见形态 */
    private static final Pattern VALID_KEYWORD = Pattern.compile("^[A-Za-z0-9_.#-]+$");
    /** 从问题里兜底抽取英文/数字 token */
    private static final Pattern ASCII_TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    /** 代码里几乎不会作为标识符出现的常见英文词，兜底抽取时过滤掉 */
    private static final Set<String> COMMON_WORDS = new HashSet<>(Arrays.asList(
            "the", "and", "for", "how", "what", "why", "when", "where", "does", "with",
            "this", "that", "from", "into", "are", "was", "were", "you", "can", "not"
    ));

    private static final String REWRITE_PROMPT =
            "你是一个代码检索助手。把用户的问题改写成若干『可能在代码库里出现的标识符』，用于关键词检索。\n"
            + "要求：\n"
            + "1. 只输出候选词，用英文逗号分隔，不要解释、不要编号、不要换行。\n"
            + "2. 每个候选词尽量是类名、方法名、字段名、表名、枚举值或其英文单词（如 order、timeout、expire、OrderService、handleOrderTimeout）。\n"
            + "3. 中英文都可以给：先给英文单词，再给可能的类名/方法名写法。\n"
            + "4. 最多 8 个，按相关度从高到低。\n"
            + "5. 不要输出代码、不要输出 SQL。\n"
            + "用户问题：";

    @Resource
    private AiProvider aiProvider;

    /**
     * @param question   用户原始问题
     * @param model      角色配置的模型（可空，走厂商默认）
     * @param apiKey     角色 API Key，必填——没有它就无法改写
     * @param baseUrl    厂商 base-url（可空）
     * @param provider   厂商标识（可空）
     * @return 候选关键词；任何失败都返回兜底结果而非抛错，保证对话不被打断
     */
    public List<String> rewrite(String question, String model, String apiKey, String baseUrl, String provider) {
        if (question == null || question.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> fromModel = rewriteByModel(question, model, apiKey, baseUrl, provider);
        if (!fromModel.isEmpty()) {
            return fromModel;
        }
        // 模型改写不可用时，至少把问题里原生的英文标识符捞出来
        return fallbackKeywords(question);
    }

    private List<String> rewriteByModel(String question, String model, String apiKey, String baseUrl, String provider) {
        if (apiKey == null || apiKey.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            AiRequest request = AiRequest.of(model, Collections.singletonList(
                    AiMessage.user(REWRITE_PROMPT + question)));
            request.setTemperature(TEMPERATURE);
            request.setApiKey(apiKey);
            request.setBaseUrl(baseUrl);
            request.setProvider(provider);

            AiResponse response = aiProvider.chat(request);
            String content = response == null ? null : response.getContent();
            List<String> keywords = parseKeywords(content);
            // 只记条数，不打问题原文与候选词——业务问题属于用户数据
            log.info("查询改写完成：keywords={}", keywords.size());
            return keywords;
        } catch (Exception e) {
            log.warn("查询改写失败，回退到问题内的英文 token: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 解析逗号/换行分隔的候选词，做去重、长度与字符校验 */
    private List<String> parseKeywords(String content) {
        if (content == null || content.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String raw : content.split("[,，、\\s\\n]+")) {
            String word = raw.trim();
            if (word.length() < MIN_KEYWORD_LENGTH) {
                continue;
            }
            if (!VALID_KEYWORD.matcher(word).matches()) {
                continue;
            }
            result.add(word);
            if (result.size() >= MAX_KEYWORDS) {
                break;
            }
        }
        return new ArrayList<>(result);
    }

    /** 兜底：直接从问题里抽英文标识符 */
    private List<String> fallbackKeywords(String question) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = ASCII_TOKEN.matcher(question);
        while (matcher.find() && result.size() < MAX_KEYWORDS) {
            String token = matcher.group();
            if (!isCommonWord(token)) {
                result.add(token);
            }
        }
        return new ArrayList<>(result);
    }

    /** 过滤掉代码里几乎不会作为标识符出现的常见英文词 */
    private boolean isCommonWord(String token) {
        return COMMON_WORDS.contains(token.toLowerCase(Locale.ROOT));
    }
}
