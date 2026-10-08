package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiKnowledgeDocumentEntity;

import java.util.List;

@Mapper
public interface AiKnowledgeDocumentMapper {
    AiKnowledgeDocumentEntity getBySourceAndPath(@Param("sourceId") Long sourceId, @Param("path") String path);

    List<String> listPathsBySource(@Param("sourceId") Long sourceId);

    long countBySource(@Param("sourceId") Long sourceId);

    int insert(AiKnowledgeDocumentEntity entity);

    int update(AiKnowledgeDocumentEntity entity);

    int updateChunkCount(@Param("id") Long id, @Param("chunkCount") int chunkCount);

    int deleteBySourceAndPath(@Param("sourceId") Long sourceId, @Param("path") String path);

    int deleteBySource(@Param("sourceId") Long sourceId);
}
