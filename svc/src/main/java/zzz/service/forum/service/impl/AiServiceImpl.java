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

    /** 流式增量事件：每段增量各自推一条，前端逐段追加，形成打字机效果 */
    private static final String EVENT_DELTA = "delta";
    /** 流正常结束。带空 data——全文已由 delta 推完，这里再带一次全文会让前端重复追加 */
    private static final String EVENT_DONE = "done";
    /** 安全拦截：要求前端把已显示的内容整段替换掉（增量已经出去了，收不回来，只能纠正） */
    private static final String EVENT_RESET = "reset";

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
        // 建会话时就校验角色可用（Key + 项目索引状态），避免"能建会话、一发消息才报错"
        ensureChatReady(agent, buildAiRequest(agent, new ArrayList<>()));
        return aiConversationService.create(userId, agentId, title);
    }

    @Override
    public List<AiConversationEntity> listConversations(Long userId) {
        return aiConversationService.listByUser(userId);
    }

    @Override
    public boolean closeConversation(Long userId, Long conversationId) {
        AiConversationEntity conv = aiConversationService.getById(conversationId);
        // 原来直接返回 service 的 false：会话不存在或不是自己的，前端收到的是"没报错的成功"
        if (conv == null || !conv.getUserid().equals(userId)) {
            throw new BaseBusinessException("会话不存在或无权访问");
        }
        return aiConversationService.close(conversationId, userId);
    }

    @Override
    public boolean reopenConversation(Long userId, Long conversationId) {
        AiConversationEntity conv = aiConversationService.getById(conversationId);
        if (conv == null || !conv.getUserid().equals(userId)) {
            throw new BaseBusinessException("会话不存在或无权访问");
        }
        // 续聊要能真正聊起来：角色还在、Key 还在、项目还是 READY，否则"打开了"也发不出消息
        AiAgentEntity agent = requireAgent(conv);
        ensureChatReady(agent, buildAiRequest(agent, new ArrayList<>()));
        return aiConversationService.reopen(conversationId, userId);
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

            // 4. 流式调用：增量即时推送，累积的同时做安全拦截，完成后落库
            StringBuilder reply = new StringBuilder();
            aiProvider.chatStream(aiRequest, new AiStreamListener() {
                /** 命中安全拦截后置位，后续增量一律丢弃，不再推给客户端 */
                private boolean blocked = false;

                @Override
                public void onToken(String delta) {
                    if (blocked) {
                        return;
                    }
                    reply.append(delta);
                    // 先判后发：命中的这一段留在服务端，不推出去。
                    // 打字机效果意味着内容已经开始外送，安全拦截只能"尽早断"而不是"事前拦"，
                    // 所以这里用增量后的全文做判断，尽量把命中的那段挡在推送之前。
                    if (aiSafetyGuard.containsRestrictedOutput(reply.toString())) {
                        blocked = true;
                        try {
                            emitter.send(sseEvent(EVENT_RESET, aiSafetyGuard.refusal()));
                        } catch (Exception ignored) {
                        }
                        return;
                    }
                    try {
                        emitter.send(sseEvent(EVENT_DELTA, delta));
                    } catch (Exception ignored) {
                        // 客户端断开等：继续把内容读完并落库，避免历史记录缺失
                    }
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
                        // 落库用拒答文案而非模型原文：历史回看时也不该看到被拦截的内容
                        String safeReply = blocked ? aiSafetyGuard.refusal() : reply.toString();
                        aiChatMessageService.saveAndCache(request.getConversationId(), "AI", "ai", safeReply);
                        // done 不再携带正文，正文已经由 delta 推完
                        emitter.send(sseEvent(EVENT_DONE, null));
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

    @Override
    public boolean deleteMessage(Long userId, Long messageId) {
        AiChatMessageEntity message = aiChatMessageService.getById(messageId);
        if (message == null) {
            throw new BaseBusinessException("消息不存在或已删除");
        }
        // 消息本身不存用户，归属要通过它所属的会话判断
        AiConversationEntity conv = aiConversationService.getById(message.getConversationid());
        if (conv == null || !conv.getUserid().equals(userId)) {
            throw new BaseBusinessException("无权删除该消息");
        }
        return aiChatMessageService.delete(messageId) != null;
    }

    @Override
    public boolean deleteConversation(Long userId, Long conversationId) {
        AiConversationEntity conv = aiConversationService.getById(conversationId);
        if (conv == null || !conv.getUserid().equals(userId)) {
            throw new BaseBusinessException("会话不存在或无权访问");
        }
        if (!aiConversationService.delete(conversationId, userId)) {
            throw new BaseBusinessException("会话删除失败");
        }
        // 消息行由外键级联删除，但 Redis 热上下文不会跟着走，得手工清
        aiChatMessageService.clearCache(conversationId);
        return true;
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
        // 会话列表只查 Status=1，已关闭的会话不能再发消息，否则"关闭"只等于从列表消失
        if (conv.getStatus() == null || conv.getStatus() != 1) {
            throw new BaseBusinessException("会话已关闭，请新建会话");
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
