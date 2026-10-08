package zzz.service.forum.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import zzz.service.forum.context.UserContext;
import zzz.service.forum.domain.AiChatMessageEntity;
import zzz.service.forum.domain.AiConversationEntity;
import zzz.service.forum.domain.dto.AiCreateConversationRequest;
import zzz.service.forum.domain.dto.AiSendRequest;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.service.AiService;
import zzz.service.forum.utils.BaseBusinessException;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * AI 对话接口：会话管理 + 发送消息 + 历史记录。
 * 路径约定与 forum(/forum)、chat(/chat) 一致：不带 /api 前缀（前端经 /api 代理转发）。
 */
@RestController
@RequestMapping("/ai/chat")
public class AiChatController {

    private static final Logger logger = LoggerFactory.getLogger(AiChatController.class);

    @Resource
    private AiService aiService;

    /** 创建 AI 会话 */
    @PostMapping("/create")
    public R<AiConversationEntity> create(@RequestBody AiCreateConversationRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(aiService.createConversation(userId, request));
        } catch (Exception e) {
            logger.warn("create conversation error", e);
            return R.fail(e.getMessage());
        }
    }

    /** 我的会话列表 */
    @GetMapping("/conversations")
    public R<?> conversations(@RequestParam(required = false) Integer page,
                              @RequestParam(required = false) Integer pageSize,
                              @RequestParam(required = false) Integer status) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        if (page != null || pageSize != null) {
            return R.ok(aiService.pageConversations(userId,
                    page == null ? 1 : page, pageSize == null ? 20 : pageSize, status));
        }
        return R.ok(aiService.listConversations(userId));
    }

    /** 关闭会话 */
    @PostMapping("/close")
    public R<Boolean> close(@RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(aiService.closeConversation(userId, parseConversationId(body.get("conversationId"))));
        } catch (Exception e) {
            logger.warn("close conversation error", e);
            return R.fail(e.getMessage());
        }
    }

    /**
     * 解析请求体里的会话ID。
     * 直接 `Long.valueOf` 会把 `For input string: "abc"` 这种 Java 异常信息回给用户，
     * 既看不懂也不该外泄，这里统一换成可读提示。
     */
    private Long parseConversationId(Object raw) {
        if (raw == null) {
            throw new BaseBusinessException("会话ID不能为空");
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new BaseBusinessException("会话ID格式不正确");
        }
    }

    /** 重新打开已关闭的会话，继续对话 */
    @PostMapping("/reopen")
    public R<Boolean> reopen(@RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(aiService.reopenConversation(userId, parseConversationId(body.get("conversationId"))));
        } catch (Exception e) {
            logger.warn("reopen conversation error", e);
            return R.fail(e.getMessage());
        }
    }

    /** 为早期未绑定角色的历史会话补绑角色，补绑后可继续对话 */
    @PostMapping("/bindAgent")
    public R<Boolean> bindAgent(@RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(aiService.bindConversationAgent(userId,
                    parseConversationId(body.get("conversationId")), parseAgentId(body.get("agentId"))));
        } catch (Exception e) {
            logger.warn("bind conversation agent error", e);
            return R.fail(e.getMessage());
        }
    }

    private Long parseAgentId(Object raw) {
        if (raw == null) {
            throw new BaseBusinessException("角色ID不能为空");
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new BaseBusinessException("角色ID格式不正确");
        }
    }

    /** 发送消息（一次性返回） */
    @PostMapping("/send")
    public R<Map<String, Object>> send(@RequestBody AiSendRequest request) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(aiService.chat(userId, request));
        } catch (Exception e) {
            logger.warn("ai send error", e);
            return R.fail(e.getMessage());
        }
    }

    /** 发送消息（SSE 流式输出，打字机效果） */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AiSendRequest request) {
        Long userId = UserContext.getUserId();
        SseEmitter emitter = new SseEmitter(0L); // 不设超时
        if (userId == null) {
            // 用 SSE error 事件回话，不要 completeWithError：后者会把响应变成 HTTP 500，
            // 前端只拿到"发送失败：HTTP 500"，既不知道是登录失效，也没法据此跳登录页
            try {
                emitter.send(SseEmitter.event().name("error").data("登录已过期，请重新登录"));
                emitter.complete();
            } catch (Exception ignored) {
            }
            return emitter;
        }
        aiService.chatStream(userId, request, emitter);
        return emitter;
    }

    /** 删除整个会话及其全部消息（业务问答页右键删除） */
    @DeleteMapping("/conversation/{id}")
    public R<Boolean> deleteConversation(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(aiService.deleteConversation(userId, id));
        } catch (Exception e) {
            logger.warn("delete conversation error", e);
            return R.fail(e.getMessage());
        }
    }

    /** 删除单条消息（业务问答页右键删除） */
    @DeleteMapping("/message/{id}")
    public R<Boolean> deleteMessage(@PathVariable Long id) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(aiService.deleteMessage(userId, id));
        } catch (Exception e) {
            logger.warn("delete message error", e);
            return R.fail(e.getMessage());
        }
    }

    /** 会话历史消息 */
    @GetMapping("/history")
    public R<List<AiChatMessageEntity>> history(@RequestParam("conversationId") Long conversationId) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        try {
            return R.ok(aiService.history(userId, conversationId));
        } catch (Exception e) {
            logger.warn("ai history error", e);
            return R.fail(e.getMessage());
        }
    }
}
