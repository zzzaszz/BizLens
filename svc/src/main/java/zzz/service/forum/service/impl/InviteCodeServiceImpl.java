package zzz.service.forum.service.impl;

import com.alibaba.cloud.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zzz.service.forum.domain.AiInviteCodeEntity;
import zzz.service.forum.domain.dto.InviteCodeView;
import zzz.service.forum.mapper.AiInviteCodeMapper;
import zzz.service.forum.service.InviteCodeService;
import zzz.service.forum.utils.AuthBusinessException;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InviteCodeServiceImpl implements InviteCodeService {

    private static final String CODE_CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 8;
    private static final int INVITE_MAX_ACTIVE_PER_USER = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource
    private AiInviteCodeMapper inviteCodeMapper;

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Value("${auth.invite-expire-days:7}")
    private int inviteExpireDays;

    @Override
    public InviteCodeView create(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        long active = inviteCodeMapper.countActiveByCreator(userId);
        if (active >= INVITE_MAX_ACTIVE_PER_USER) {
            throw new AuthBusinessException(500, "未使用的邀请码已达到上限", null);
        }

        for (int attempt = 0; attempt < 5; attempt++) {
            AiInviteCodeEntity entity = new AiInviteCodeEntity();
            entity.setId(snowflakeIdGenerator.nextId());
            entity.setCode(generateCode());
            entity.setCreatedBy(userId);
            entity.setStatus(0);
            entity.setExpireAt(now.plusDays(inviteExpireDays));
            try {
                inviteCodeMapper.insert(entity);
                InviteCodeView view = new InviteCodeView();
                view.setId(entity.getId());
                view.setCode(entity.getCode());
                view.setStatus(0);
                view.setExpired(false);
                view.setExpireAt(entity.getExpireAt());
                view.setCreatedAt(now);
                return view;
            } catch (DuplicateKeyException e) {
                // 极低概率的随机碰撞由唯一索引兜底，重试生成。
            }
        }
        throw new RuntimeException("邀请码生成失败，请稍后重试");
    }

    @Override
    public List<InviteCodeView> list(Long userId) {
        return inviteCodeMapper.listByCreator(userId);
    }

    @Override
    public void revoke(Long userId, Long id) {
        if (id == null || inviteCodeMapper.revoke(id, userId) != 1) {
            throw new AuthBusinessException(500, "邀请码不存在、已使用或已过期", null);
        }
    }

    @Override
    @Transactional
    public Map<String, Object> claim(String code, Long userId) {
        if (StringUtils.isBlank(code)) {
            throw new AuthBusinessException(500, "邀请码无效或已过期", null);
        }
        String normalized = code.trim().toUpperCase();
        if (inviteCodeMapper.claim(normalized, userId) != 1) {
            throw new AuthBusinessException(500, "邀请码无效或已过期", null);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("code", normalized);
        result.put("claimed", true);
        return result;
    }

    private String generateCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
        }
        return builder.toString();
    }
}
