package zzz.service.forum.service.impl;

import org.springframework.stereotype.Service;
import zzz.service.forum.domain.AiConversationEntity;
import zzz.service.forum.domain.dto.PageResult;
import zzz.service.forum.mapper.AiConversationMapper;
import zzz.service.forum.service.AiConversationService;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;

/**
 * AI 会话服务实现类
 */
@Service("aiConversationService")
public class AiConversationServiceImpl implements AiConversationService {

    @Resource
    private AiConversationMapper aiConversationMapper;
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public AiConversationEntity getById(Long id) {
        return id == null ? null : aiConversationMapper.getById(id);
    }

    @Override
    public List<AiConversationEntity> listByUser(Long userId) {
        return aiConversationMapper.listByUser(userId);
    }

    @Override
    public PageResult<AiConversationEntity> pageByUser(Long userId, int page, int pageSize, Integer status) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long offset = (long) (safePage - 1) * safeSize;
        Integer safeStatus = status != null && (status == 0 || status == 1) ? status : null;
        return new PageResult<>(aiConversationMapper.pageByUser(userId, offset, safeSize, safeStatus),
                aiConversationMapper.countByUser(userId, safeStatus), safePage, safeSize);
    }

    @Override
    public AiConversationEntity create(Long userId, Long agentId, String title) {
        AiConversationEntity entity = new AiConversationEntity();
        entity.setId(snowflakeIdGenerator.nextId());
        entity.setUserid(userId);
        entity.setAgentid(agentId);
        entity.setTitle(title == null || title.isEmpty() ? "AI 对话" : title);
        entity.setStatus(1);
        Date now = new Date();
        entity.setCreatedat(now);
        entity.setUpdatedat(now);
        aiConversationMapper.insert(entity);
        return entity;
    }

    @Override
    public boolean close(Long conversationId, Long userId) {
        AiConversationEntity conv = aiConversationMapper.getById(conversationId);
        if (conv == null || !conv.getUserid().equals(userId)) {
            return false;
        }
        return aiConversationMapper.updateStatus(conversationId, 0, new Date()) > 0;
    }

    @Override
    public boolean reopen(Long conversationId, Long userId) {
        AiConversationEntity conv = aiConversationMapper.getById(conversationId);
        if (conv == null || !conv.getUserid().equals(userId)) {
            return false;
        }
        return aiConversationMapper.updateStatus(conversationId, 1, new Date()) > 0;
    }

    @Override
    public boolean bindAgent(Long conversationId, Long userId, Long agentId) {
        AiConversationEntity conv = aiConversationMapper.getById(conversationId);
        if (conv == null || !conv.getUserid().equals(userId)) {
            return false;
        }
        return aiConversationMapper.bindAgent(conversationId, agentId, new Date()) > 0;
    }

    @Override
    public boolean delete(Long conversationId, Long userId) {
        AiConversationEntity conv = aiConversationMapper.getById(conversationId);
        if (conv == null || !conv.getUserid().equals(userId)) {
            return false;
        }
        return aiConversationMapper.deleteById(conversationId) > 0;
    }
}
