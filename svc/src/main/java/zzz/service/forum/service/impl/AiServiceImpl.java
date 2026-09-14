package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import zzz.service.forum.domain.AiConversationEntity;
import zzz.service.forum.domain.dto.AiCreateConversationRequest;
import zzz.service.forum.domain.dto.AiSendRequest;
import zzz.service.forum.service.AiAgentService;
import zzz.service.forum.service.AiChatMessageService;
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

    @Resource
    private AiConversationService aiConversationService;
    @Resource
    private AiChatMessageService aiChatMessageService;
    @Resource
    private AiAgentService aiAgentService;
    @Resource
    private AiProvider aiProvider;
    @Resource
    private RedisService redisService;
    @Resource
    private CryptoService cryptoService;
    @Resource
    private AiSafetyGuard aiSafetyGuard;

    /** 系统默认 Key（来自环境变量 DEEPSEEK_API_KEY），角色未填 Key 时兜底 */
    @Value("${ai.deepseek.api-key:}")
    private String systemApiKey;

    @Override
    public AiConversationEntity createConversation(Long userId, AiCreateConversationRequest request) {
        Long agentId = request != null ? request.getAgentId() : null;
        String title = request != null ? request.getTitle() : null;
        if (agentId != null && aiAgentService.getById(agentId) == null) {
            throw new BaseBusinessException("AI 角色不存在");
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

        // 2. 组装 messages = 角色设定 + 热上下文最近 N 条 + 当前问题
        List<AiMessage> messages = buildMessages(conv, request.getMessage());

        // 3. 校验 Key：角色自带 Key 或系统默认 Key 至少有一个，否则提示配置
        AiRequest aiRequest = buildAiRequest(conv, messages);
        ensureApiKey(aiRequest);

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

            // 2. 组装 messages
            List<AiMessage> messages = buildMessages(conv, request.getMessage());

            // 3. 校验 Key：角色自带 Key 或系统默认 Key 至少有一个，否则提示配置
            AiRequest aiRequest = buildAiRequest(conv, messages);
            ensureApiKey(aiRequest);

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
                        emitter.send(SseEmitter.event().name("error").data(t.getMessage() != null ? t.getMessage() : "AI 调用失败"));
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
                        emitter.send(SseEmitter.event().name("done").data(safeReply));
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
                emitter.send(SseEmitter.event().name("error").data(e.getMessage() != null ? e.getMessage() : "请求失败"));
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
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

    /** 组装 messages = system(角色设定) + 热上下文最近 N 条 + 当前问题 */
    private List<AiMessage> buildMessages(AiConversationEntity conv, String message) {
        List<AiMessage> messages = new ArrayList<>();
        if (conv.getAgentid() != null) {
            AiAgentEntity agent = aiAgentService.getById(conv.getAgentid());
            if (agent == null) {
                throw new BaseBusinessException("AI 角色不存在或已删除");
            }
            if (agent.getSystemprompt() != null && !agent.getSystemprompt().isEmpty()) {
                messages.add(AiMessage.system(agent.getSystemprompt()));
            }
        }
        for (AiChatMessageEntity h : aiChatMessageService.loadHistoryCached(conv.getId(), CONTEXT_RECENT)) {
            messages.add("AI".equalsIgnoreCase(h.getSendertype())
                    ? AiMessage.assistant(h.getContent())
                    : AiMessage.user(h.getContent()));
        }
        messages.add(AiMessage.user(message));
        return messages;
    }

    /** 组装 AiRequest：角色自带 Key（解密）优先，否则走系统默认 */
    private AiRequest buildAiRequest(AiConversationEntity conv, List<AiMessage> messages) {
        AiRequest aiRequest = AiRequest.of(null, messages);
        if (conv.getAgentid() != null) {
            AiAgentEntity agent = aiAgentService.getById(conv.getAgentid());
            if (agent != null) {
                aiRequest.setModel(agent.getModel());
                // 用户自带的 API Key 仅在此处解密，用完即弃
                aiRequest.setApiKey(cryptoService.decrypt(agent.getApikeyenc()));
                aiRequest.setBaseUrl(agent.getApibaseurl());
                aiRequest.setProvider(agent.getProvider());
            }
        }
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
     * 校验是否有可用 Key：角色自带 Key 或系统默认 Key 至少一个，
     * 二者皆无时对话直接提示配置，避免静默返回空回复。
     */
    private void ensureApiKey(AiRequest request) {
        boolean hasRoleKey = request.getApiKey() != null && !request.getApiKey().isEmpty();
        boolean hasSystemKey = systemApiKey != null && !systemApiKey.isEmpty();
        if (!hasRoleKey && !hasSystemKey) {
            throw new BaseBusinessException("未配置 API Key：请为角色填写 API Key，或在服务端配置环境变量 DEEPSEEK_API_KEY");
        }
    }
}
