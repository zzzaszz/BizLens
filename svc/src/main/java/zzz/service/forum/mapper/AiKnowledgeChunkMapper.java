package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiKnowledgeChunkEntity;
import zzz.service.forum.domain.dto.AiKnowledgeChunkHit;

import java.util.List;

@Mapper
public interface AiKnowledgeChunkMapper {
    int insert(AiKnowledgeChunkEntity entity);

    int deleteByDocument(@Param("documentId") Long documentId);

    List<AiKnowledgeChunkHit> searchByKeywords(@Param("sourceIds") List<Long> sourceIds,
                                               @Param("keywords") List<String> keywords,
                                               @Param("limit") int limit);

    List<AiKnowledgeChunkHit> searchByVector(@Param("sourceIds") List<Long> sourceIds,
                                             @Param("vector") String vector,
                                             @Param("limit") int limit);
}
