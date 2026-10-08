package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiCodeProjectEntity;

import java.util.List;

@Mapper
public interface AiCodeProjectMapper {
    AiCodeProjectEntity getById(@Param("id") Long id);

    List<AiCodeProjectEntity> listByUser(@Param("userId") Long userId);

    List<AiCodeProjectEntity> pageByUser(@Param("userId") Long userId,
                                         @Param("offset") long offset,
                                         @Param("pageSize") int pageSize);

    long countByUser(@Param("userId") Long userId);

    int insert(AiCodeProjectEntity entity);

    int update(AiCodeProjectEntity entity);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int startIndex(@Param("id") Long id, @Param("totalFiles") int totalFiles);

    int updateIndexProgress(@Param("id") Long id, @Param("processedFiles") int processedFiles,
                            @Param("skippedFiles") int skippedFiles, @Param("currentFile") String currentFile);

    /** 索引成功：置 READY、回写 commit 与本次耗时 */
    int markIndexed(@Param("id") Long id, @Param("status") String status,
                    @Param("gitCommit") String gitCommit, @Param("durationMs") Long durationMs);

    /** 索引失败：状态置 FAILED 并记录可排查的原因 */
    int markFailed(@Param("id") Long id, @Param("error") String error);

    /**
     * 索引过程中的心跳：只推进 updated_at。
     * 用于区分"索引真的在跑"和"进程已经没了但状态没回写"。
     */
    int touchIndexHeartbeat(@Param("id") Long id);

    /**
     * 启动时收敛上一轮进程中断的索引状态（QUEUED/INDEXING → FAILED）。
     * 只重置超过 graceSeconds 秒没有心跳的，避免误伤其它实例正在跑的任务。
     */
    int resetStuckIndexing(@Param("reason") String reason, @Param("graceSeconds") int graceSeconds);

    int delete(@Param("id") Long id);
}
