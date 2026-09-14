package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zzz.service.forum.ai.AiMessage;
import zzz.service.forum.ai.AiProvider;
import zzz.service.forum.ai.AiRequest;
import zzz.service.forum.ai.AiResponse;
import zzz.service.forum.ai.AiSafetyGuard;
import zzz.service.forum.ai.AiStreamListener;
import zzz.service.forum.domain.AiAgentEntity;
import zzz.service.forum.domain.AiChatMessageEntity;
import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.AiConversationEntity;
import zzz.service.forum.domain.dto.AiCodeChunkHit;
import zzz.service.forum.domain.dto.AiCreateConversationRequest;
import zzz.service.forum.domain.dto.AiSendRequest;
import zzz.service.forum.service.AiAgentService;
import zzz.service.forum.service.AiChatMessageService;
import zzz.service.forum.service.AiCodeProjectService;
import zzz.service.forum.service.AiConversationService;
import zzz.service.forum.service.AiService;
import zzz.service.forum.service.RedisService;
import zzz.service.forum.utils.BaseBusinessException;
import zzz.service.forum.utils.CryptoService;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 对话业务服务实现类
 */
@Service("aiService")
public class AiServiceImpl implements AiService {

    private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

    /** 组装模型上下文时携带的最近消息条数 */
    private static final int CONTEXT_RECENT = 20;
    /** 单条消息最大长度（超出截断，控制 token 成本） */
    private static final int MAX_MESSAGE_LENGTH = 2000;
    /** 限流 key 前缀：ai:chat:rate:{userId} */
    private static final String RATE_KEY_PREFIX = "ai:chat:rate:";
    /** 每分钟最多调用次数 */
    private static final int RATE_LIMIT = 20;

    /**
     * 角色没填「角色设定」时的兜底提示。
     * 没有它，buildMessages 会一条 system 消息都不加，模型就以裸模型身份回话
     * （表现是自称"我是 DeepSeek……"），这也是角色设定为空时的默认行为。
     */
    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是 BizLens AI 的业务问答助手。职责是解释代码背后的业务规则和处理流程，帮助用户理解业务，而不是提供代码。"
            + "你可以说明业务含义、状态流转、校验规则和处理步骤，但不得输出源代码、SQL、配置、密钥或内部文件路径。";

    /** 召回成功时注入的上下文抬头与使用约束 */
    private static final String KNOWLEDGE_PREFIX =
            "以下是系统从项目「%s」的代码库中按关键词检索到的片段，供你理解业务逻辑。\n"
            + "使用约束：\n"
            + "1. 只基于这些片段解释业务规则与处理流程，不要臆测片段之外的实现。\n"
            + "2. 严禁把片段里的源代码、SQL、字段名原文复述给用户——用业务语言转述。\n"
            + "3. 片段不足以回答时，如实说明还缺什么信息，不要编造。\n";

    /** 召回为空时如实告知模型，避免它一本正经地编业务规则 */
    private static final String NO_KNOWLEDGE_HINT =
            "本轮没有从项目「%s」的代码库中检索到相关片段。请如实告知用户当前无法基于该项目回答，"
            + "并建议他把问题描述得更具体（例如提到具体的业务名称、单据、字段或操作），不要编造业务规则。";

    @Resource
    private AiConversationService aiConversationService;
    @Resource
    private AiChatMessageService aiChatMessageService;
    @Resource
    private AiAgentService aiAgentService;
    @Resource
    private AiCodeProjectService aiCodeProjectService;
    @Resource
    private AiProvider aiProvider;
    @Resource
    private RedisService redisService;
    @Resource
    private CryptoService cryptoService;
    @Resource
    private AiSafetyGuard aiSafetyGuard;
    @Resource
    private AiCodeRetrievalService aiCodeRetrievalService;

    @Override
    public AiConversationEntity createConversation(Long userId, AiCreateConversationRequest request) {
        Long agentId = request != null ? request.getAgentId() : null;
        String title = request != null ? request.getTitle() : null;
        // 会话必须绑定角色：角色由代码项目创建，因此每个会话都能追溯到一份知识库
        if (agentId == null) {
            throw new BaseBusinessException("请选择 AI 角色：角色由代码项目创建");
        }
        AiAgentEntity agent = aiAgentService.getById(agentId);
        if (agent == null) {
            throw new BaseBusinessException("AI 角色不存在或已删除");
        }
        if (!userId.equals(agent.getUserid())) {
            throw new BaseBusinessException("无权使用他人创建的角色");
        }
        return aiConversationService.create(userId, agentId, title);
    }

    @Override
    public List<AiConversationEntity> listConversations(Long userId) {
        return aiConversationService.listByUser(userId);
    }

    @Override
    public boolean closeConversation(Long userId, Long conversationId) {
        return aiConversationService.close(conversationId, userId);
    }

    @Override
    public Map<String, Object> chat(Long userId, AiSendRequest request) {
        // 1. 校验 + 限流 + 截断
        AiConversationEntity conv = checkAndPrepare(userId, request);
        AiAgentEntity agent = requireAgent(conv);

        // 2. 先校验凭据再组装上下文——知识库召回要多花一次模型调用，没配好就不该发起
        AiRequest aiRequest = buildAiRequest(agent, new ArrayList<>());
        AiCodeProjectEntity project = ensureChatReady(agent, aiRequest);

        // 3. 组装 messages = 角色设定 + 知识库召回 + 热上下文最近 N 条 + 当前问题
        aiRequest.setMessages(buildMessages(conv, agent, project, aiRequest, request.getMessage()));

        // 4. 调用模型
        String reply;
        try {
            AiResponse resp = aiProvider.chat(aiRequest);
            reply = resp.getContent();
            if (aiSafetyGuard.containsRestrictedOutput(reply)) {
                reply = aiSafetyGuard.refusal();
            }
        } catch (Exception e) {
            log.warn("AI 调用失败: {}", e.getMessage());
            throw new BaseBusinessException("AI 服务暂时不可用，请稍后再试");
        }

        // 4. 落库用户消息 + AI 回复（同步更新热上下文）
        aiChatMessageService.saveAndCache(request.getConversationId(), "USER", String.valueOf(userId), request.getMessage());
        aiChatMessageService.saveAndCache(request.getConversationId(), "AI", "ai", reply);

        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", request.getConversationId());
        result.put("reply", reply);
        result.put("history", aiChatMessageService.listByConversation(request.getConversationId()));
        return result;
    }

    @Override
    public void chatStream(Long userId, AiSendRequest request, SseEmitter emitter) {
        try {
            // 1. 校验 + 限流 + 截断
            AiConversationEntity conv = checkAndPrepare(userId, request);
            AiAgentEntity agent = requireAgent(conv);

            // 2. 先校验凭据再组装上下文——知识库召回要多花一次模型调用，没配好就不该发起
            AiRequest aiRequest = buildAiRequest(agent, new ArrayList<>());
            AiCodeProjectEntity project = ensureChatReady(agent, aiRequest);

            // 3. 组装 messages = 角色设定 + 知识库召回 + 热上下文 + 当前问题
            aiRequest.setMessages(buildMessages(conv, agent, project, aiRequest, request.getMessage()));

            // 4. 流式调用，边推送边累积，完成后落库
            StringBuilder reply = new StringBuilder();
            aiProvider.chatStream(aiRequest, new AiStreamListener() {
                @Override
                public void onToken(String delta) {
                    reply.append(delta);
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("AI 流式调用失败: {}", t.getMessage());
                    try {
                        emitter.send(sseEvent("error", t.getMessage() != null ? t.getMessage() : "AI 调用失败"));
                        emitter.complete();
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onDone() {
                    try {
                        aiChatMessageService.saveAndCache(request.getConversationId(), "USER", String.valueOf(userId), request.getMessage());
                        String safeReply = aiSafetyGuard.containsRestrictedOutput(reply.toString())
                                ? aiSafetyGuard.refusal() : reply.toString();
                        aiChatMessageService.saveAndCache(request.getConversationId(), "AI", "ai", safeReply);
                        emitter.send(sseEvent("done", safeReply));
                        emitter.complete();
                    } catch (Exception e) {
                        log.warn("流式完成落库失败: {}", e.getMessage());
                        try {
                            emitter.completeWithError(e);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        } catch (Exception e) {
            try {
                emitter.send(sseEvent("error", e.getMessage() != null ? e.getMessage() : "请求失败"));
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 构造 SSE 事件。
     * <p>
     * 注意：SSE 的 data 字段不能包含裸换行——第一个空行会被客户端当成事件分隔符，
     * 导致 Markdown 回复只送出第一行、其余全部丢失。因此按行拆成多个 data: 字段，
     * 由客户端按 SSE 规范用换行拼回。错误信息同样可能多行，走同一处理。
     */
    private SseEmitter.SseEventBuilder sseEvent(String name, String payload) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(name);
        if (payload == null || payload.isEmpty()) {
            return builder.data("");
        }
        String normalized = payload.replace("\r\n", "\n").replace('\r', '\n');
        for (String line : normalized.split("\n", -1)) {
            builder.data(line);
        }
        return builder;
    }

    @Override
    public List<AiChatMessageEntity> history(Long userId, Long conversationId) {
        AiConversationEntity conv = aiConversationService.getById(conversationId);
        if (conv == null || !conv.getUserid().equals(userId)) {
            throw new BaseBusinessException("会话不存在或无权访问");
        }
        return aiChatMessageService.listByConversation(conversationId);
    }

    // ==================== 私有方法 ====================

    /** 校验会话归属 + 用户限流 + 消息截断 */
    private AiConversationEntity checkAndPrepare(Long userId, AiSendRequest request) {
        if (!checkRateLimit(userId)) {
            throw new BaseBusinessException("请求过于频繁，请稍后再试");
        }
        if (request.getConversationId() == null) {
            throw new BaseBusinessException("会话ID不能为空");
        }
        String message = request.getMessage();
        if (message == null || message.trim().isEmpty()) {
            throw new BaseBusinessException("消息内容不能为空");
        }
        if (aiSafetyGuard.isPromptInjection(message)) {
            throw new BaseBusinessException(aiSafetyGuard.refusal());
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            request.setMessage(message.substring(0, MAX_MESSAGE_LENGTH));
        }

        AiConversationEntity conv = aiConversationService.getById(request.getConversationId());
        if (conv == null || !conv.getUserid().equals(userId)) {
            throw new BaseBusinessException("会话不存在或无权访问");
        }
        return conv;
    }

    /** 解析会话绑定的角色；不存在直接拒绝 */
    private AiAgentEntity requireAgent(AiConversationEntity conv) {
        if (conv.getAgentid() == null) {
            throw new BaseBusinessException("会话未绑定角色：请先创建代码项目，再为项目创建角色");
        }
        AiAgentEntity agent = aiAgentService.getById(conv.getAgentid());
        if (agent == null) {
            throw new BaseBusinessException("AI 角色不存在或已删除");
        }
        return agent;
    }

    /**
     * 组装 messages = system(角色设定或内置默认) + system(知识库召回) + 热上下文最近 N 条 + 当前问题。
     * <p>
     * 召回会多花一次模型调用（把中文问题改写成候选标识符），因此调用方必须已通过 ensureChatReady。
     */
    private List<AiMessage> buildMessages(AiConversationEntity conv, AiAgentEntity agent,
                                          AiCodeProjectEntity project, AiRequest aiRequest, String message) {
        List<AiMessage> messages = new ArrayList<>();

        String systemPrompt = agent.getSystemprompt();
        messages.add(AiMessage.system(
                systemPrompt != null && !systemPrompt.isEmpty() ? systemPrompt : DEFAULT_SYSTEM_PROMPT));

        // 知识库召回：把项目内相关源码片段喂给模型，让它能真正回答业务问题
        List<AiCodeChunkHit> hits = aiCodeRetrievalService.retrieve(
                project.getId(), message, agent.getModel(),
                aiRequest.getApiKey(), agent.getApibaseurl(), agent.getProvider());
        String context = aiCodeRetrievalService.buildContext(hits);
        messages.add(AiMessage.system(context != null
                ? String.format(KNOWLEDGE_PREFIX, project.getName()) + context
                : String.format(NO_KNOWLEDGE_HINT, project.getName())));

        for (AiChatMessageEntity h : aiChatMessageService.loadHistoryCached(conv.getId(), CONTEXT_RECENT)) {
            messages.add("AI".equalsIgnoreCase(h.getSendertype())
                    ? AiMessage.assistant(h.getContent())
                    : AiMessage.user(h.getContent()));
        }
        messages.add(AiMessage.user(message));
        return messages;
    }

    /** 组装 AiRequest 的调用参数；API Key 仅在此处解密，用完即弃、不落日志 */
    private AiRequest buildAiRequest(AiAgentEntity agent, List<AiMessage> messages) {
        AiRequest aiRequest = AiRequest.of(agent.getModel(), messages);
        aiRequest.setApiKey(cryptoService.decrypt(agent.getApikeyenc()));
        aiRequest.setBaseUrl(agent.getApibaseurl());
        aiRequest.setProvider(agent.getProvider());
        return aiRequest;
    }

    /** 用户维度限流（Redis 每分钟计数，第一次置 60s 过期） */
    private boolean checkRateLimit(Long userId) {
        String key = RATE_KEY_PREFIX + userId;
        Long count = redisService.increment(key, 1);
        if (count != null && count == 1L) {
            redisService.expire(key, 60);
        }
        return count == null || count <= RATE_LIMIT;
    }

    /**
     * 发起对话前的校验，两项缺一不可：
     * <ol>
     *   <li>角色必须配置 API Key——不再回退服务端 DEEPSEEK_API_KEY，避免"没填 Key 也能对话"</li>
     *   <li>角色所属项目必须已完成索引（READY），否则知识库里没有片段、回答没有依据</li>
     * </ol>
     * Git 凭据不在此校验：公开仓库无需凭据，凭据只在索引任务克隆时按需使用。
     *
     * @return 角色所属项目，后续组装上下文要用
     */
    private AiCodeProjectEntity ensureChatReady(AiAgentEntity agent, AiRequest request) {
        if (request.getApiKey() == null || request.getApiKey().isEmpty()) {
            throw new BaseBusinessException("请先为角色配置 API Key");
        }
        AiCodeProjectEntity project = aiCodeProjectService.getById(agent.getProjectid());
        if (project == null) {
            throw new BaseBusinessException("角色所属代码项目不存在或已删除");
        }
        if (!"READY".equals(project.getStatus())) {
            throw new BaseBusinessException("项目「" + project.getName() + "」尚未完成索引，请先在代码项目页排队索引");
        }
        return project;
    }
}
