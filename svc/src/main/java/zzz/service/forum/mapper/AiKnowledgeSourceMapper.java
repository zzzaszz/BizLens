package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiKnowledgeSourceEntity;

import java.util.List;

@Mapper
public interface AiKnowledgeSourceMapper {
    AiKnowledgeSourceEntity getById(@Param("id") Long id);

    List<AiKnowledgeSourceEntity> listByUser(@Param("userId") Long userId);

    List<AiKnowledgeSourceEntity> pageByUser(@Param("userId") Long userId,
                                             @Param("offset") long offset,
                                             @Param("pageSize") int pageSize);

    long countByUser(@Param("userId") Long userId);

    int insert(AiKnowledgeSourceEntity entity);

    int update(AiKnowledgeSourceEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int startIndex(@Param("id") Long id, @Param("totalFiles") int totalFiles);

    int updateIndexProgress(@Param("id") Long id, @Param("processedFiles") int processedFiles,
                            @Param("skippedFiles") int skippedFiles, @Param("currentFile") String currentFile);

    int markIndexed(@Param("id") Long id, @Param("status") String status,
                    @Param("gitCommit") String gitCommit, @Param("durationMs") Long durationMs);

    int markFailed(@Param("id") Long id, @Param("error") String error);

    int touchIndexHeartbeat(@Param("id") Long id);

    int resetStuckIndexing(@Param("reason") String reason, @Param("graceSeconds") int graceSeconds);

    int delete(@Param("id") Long id);
}
