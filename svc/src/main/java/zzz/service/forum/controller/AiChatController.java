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
    public R<List<AiConversationEntity>> conversations() {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        return R.ok(aiService.listConversations(userId));
    }

    /** 关闭会话 */
    @PostMapping("/close")
    public R<Boolean> close(@RequestBody Map<String, Object> body) {
        Long userId = UserContext.getUserId();
        if (userId == null) return R.fail(401, "请先登录");
        Object id = body.get("conversationId");
        if (id == null) return R.fail("会话ID不能为空");
        return R.ok(aiService.closeConversation(userId, Long.valueOf(String.valueOf(id))));
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
            emitter.completeWithError(new IllegalStateException("请先登录"));
            return emitter;
        }
        aiService.chatStream(userId, request, emitter);
        return emitter;
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
