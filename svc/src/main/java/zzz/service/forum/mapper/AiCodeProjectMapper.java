package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiCodeProjectEntity;

import java.util.List;

@Mapper
public interface AiCodeProjectMapper {
    AiCodeProjectEntity getById(@Param("id") Long id);

    List<AiCodeProjectEntity> listByUser(@Param("userId") Long userId);

    int insert(AiCodeProjectEntity entity);

    int update(AiCodeProjectEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int markIndexed(@Param("id") Long id, @Param("status") String status,
                    @Param("gitCommit") String gitCommit);

    int delete(@Param("id") Long id);
}
