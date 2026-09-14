package zzz.service.forum.ai;

import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * 大模型调用抽象层。
 * <p>
 * 面向业务语义（会话 + 消息），不暴露具体厂商细节。
 * 换模型只需新增一个 Provider 实现，业务代码（AiService）无需改动。
 */
public interface AiProvider {

    /**
     * 一次性返回模型回复（OpenAI 兼容格式）。
     *
     * @param request 请求体（模型 / messages / 参数 / 覆盖的 key）
     * @return 模型回复
     */
    AiResponse chat(AiRequest request);

    /**
     * 流式返回模型回复（OpenAI 兼容 SSE 格式）。
     * <p>
     * 默认实现退化为一次性返回（一次 onToken 交付完整回复），
     * 支持流式的 Provider 应重写为逐段增量回调。
     *
     * @param request  请求体
     * @param listener 增量回调
     */
    default void chatStream(AiRequest request, AiStreamListener listener) {
        try {
            AiResponse resp = chat(request);
            if (resp.getContent() != null && !resp.getContent().isEmpty()) {
                listener.onToken(resp.getContent());
            }
            listener.onDone();
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    /**
     * 厂商标识：deepseek / openai / qwen。
     * 用于角色自带 Key 时选择对应 Provider。
     */
    default String providerName() {
        return "default";
    }

    /**
     * 拉取厂商可用模型列表（OpenAI 兼容的 GET {baseUrl}/models）。
     * <p>
     * 仅用于填充配置表单的下拉选项，属于辅助能力，因此默认返回空列表而非抛错；
     * 调用方在拿到空列表时自行回退到预置列表，不能因此阻断表单。
     *
     * @param baseUrl 厂商 base-url
     * @param apiKey  调用方提供的 Key
     * @return 模型 id 列表；不支持或调用失败时返回空列表
     */
    default List<String> listModels(String baseUrl, String apiKey) {
        // 防御：Provider 漏覆写时不能静默返回空，否则模型下拉会无声退化成预置列表，排查时毫无线索
        LoggerFactory.getLogger(AiProvider.class)
                .warn("{} 未实现 listModels，模型列表将回退到预置兜底", getClass().getSimpleName());
        return Collections.emptyList();
    }
}
