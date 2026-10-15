package zzz.service.forum.service;

import zzz.service.forum.domain.dto.InviteCodeView;

import java.util.List;
import java.util.Map;

public interface InviteCodeService {

    InviteCodeView create(Long userId);

    List<InviteCodeView> list(Long userId);

    void revoke(Long userId, Long id);

    Map<String, Object> claim(String code, Long userId);
}
