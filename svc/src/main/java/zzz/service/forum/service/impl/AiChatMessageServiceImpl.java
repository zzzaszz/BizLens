package zzz.service.forum.service.impl;

import org.springframework.stereotype.Service;
import zzz.service.forum.domain.AiChatMessageEntity;
import zzz.service.forum.mapper.AiChatMessageMapper;
import zzz.service.forum.service.AiChatMessageService;
import zzz.service.forum.service.RedisService;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * AI 消息服务实现类
 */
@Service("aiChatMessageService")
public class AiChatMessageServiceImpl implements AiChatMessageService {

    /** Redis 热上下文 key 前缀：ai:conversation:{conversationId} */
    private static final String REDIS_HISTORY_PREFIX = "ai:conversation:";
    /** 热上下文保留的最大消息条数 */
    private static final int REDIS_HISTORY_MAX = 50;

    @Resource
    private AiChatMessageMapper aiChatMessageMapper;
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Resource
    private RedisService redisService;

    @Override
    public AiChatMessageEntity save(Long conversationId, String senderType, String senderId, String content) {
        AiChatMessageEntity entity = new AiChatMessageEntity();
        entity.setId(snowflakeIdGenerator.nextId());
        entity.setConversationid(conversationId);
        entity.setSendertype(senderType);
        entity.setSenderid(senderId);
        entity.setContent(content);
        entity.setCreatetime(new Date());
        aiChatMessageMapper.insert(entity);
        return entity;
    }

    @Override
    public List<AiChatMessageEntity> listByConversation(Long conversationId) {
        return aiChatMessageMapper.listByConversation(conversationId);
    }

    @Override
    public List<AiChatMessageEntity> loadHistory(Long conversationId, int limit) {
        return aiChatMessageMapper.selectRecent(conversationId, limit);
    }

    @Override
    public AiChatMessageEntity saveAndCache(Long conversationId, String senderType, String senderId, String content) {
        AiChatMessageEntity entity = save(conversationId, senderType, senderId, content);
        // 更新热上下文：读缓存 + 追加 + 截断 + 写回
        List<AiChatMessageEntity> cached = readCache(conversationId);
        cached.add(entity);
        if (cached.size() > REDIS_HISTORY_MAX) {
            cached = new ArrayList<>(cached.subList(cached.size() - REDIS_HISTORY_MAX, cached.size()));
        }
        redisService.setCacheObject(REDIS_HISTORY_PREFIX + conversationId, cached);
        return entity;
    }

    @Override
    public List<AiChatMessageEntity> loadHistoryCached(Long conversationId, int limit) {
        List<AiChatMessageEntity> cached = readCache(conversationId);
        if (cached != null && !cached.isEmpty()) {
            return tail(cached, limit);
        }
        // Miss：查 PostgreSQL 回填热上下文
        List<AiChatMessageEntity> history = aiChatMessageMapper.listByConversation(conversationId);
        if (history != null && !history.isEmpty()) {
            List<AiChatMessageEntity> store = tail(history, REDIS_HISTORY_MAX);
            redisService.setCacheObject(REDIS_HISTORY_PREFIX + conversationId, store);
            return tail(store, limit);
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<AiChatMessageEntity> readCache(Long conversationId) {
        Object cached = redisService.getCacheObject(REDIS_HISTORY_PREFIX + conversationId);
        if (cached instanceof List) {
            return new ArrayList<>((List<AiChatMessageEntity>) cached);
        }
        return new ArrayList<>();
    }

    private List<AiChatMessageEntity> tail(List<AiChatMessageEntity> list, int limit) {
        if (list.size() <= limit) {
            return list;
        }
        return new ArrayList<>(list.subList(list.size() - limit, list.size()));
    }
}
