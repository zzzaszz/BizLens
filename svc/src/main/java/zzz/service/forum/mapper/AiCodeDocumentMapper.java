package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiCodeDocumentEntity;

@Mapper
public interface AiCodeDocumentMapper {
    int deleteByProject(@Param("projectId") Long projectId);

    int countByProject(@Param("projectId") Long projectId);

    int insert(AiCodeDocumentEntity entity);
}
