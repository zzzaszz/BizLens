package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiInviteCodeEntity;
import zzz.service.forum.domain.dto.InviteCodeView;

import java.util.List;

@Mapper
public interface AiInviteCodeMapper {

    int insert(AiInviteCodeEntity entity);

    long countActiveByCreator(@Param("createdBy") Long createdBy);

    List<InviteCodeView> listByCreator(@Param("createdBy") Long createdBy);

    int claim(@Param("code") String code,
              @Param("usedBy") Long usedBy);

    int revoke(@Param("id") Long id,
               @Param("createdBy") Long createdBy);
}
