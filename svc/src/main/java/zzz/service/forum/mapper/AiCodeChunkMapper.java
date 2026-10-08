package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiCodeChunkEntity;
import zzz.service.forum.domain.dto.AiCodeChunkHit;
import zzz.service.forum.domain.dto.AiCodeRelationCandidate;

import java.util.List;

@Mapper
public interface AiCodeChunkMapper {

    int insert(AiCodeChunkEntity entity);

    int deleteRelationsByProject(@Param("projectId") Long projectId);

    List<AiCodeRelationCandidate> findRelationCandidates(@Param("projectId") Long projectId);

    int insertRelation(@Param("id") Long id,
                       @Param("projectId") Long projectId,
                       @Param("sourceChunkId") Long sourceChunkId,
                       @Param("targetChunkId") Long targetChunkId,
                       @Param("relationType") String relationType);

    /** 按文件名、路径或 FILE 切片中的内容寻找目标文件。 */
    List<AiCodeChunkHit> searchFiles(@Param("projectIds") List<Long> projectIds,
                                     @Param("keywords") List<String> keywords,
                                     @Param("limit") int limit);

    /** 获取目标文件下的全部符号切片，不使用普通召回的条数上限。 */
    List<AiCodeChunkHit> listSymbolsByDocuments(@Param("documentIds") List<Long> documentIds);

    /** 从种子切片沿关系表向下递归追踪。 */
    List<AiCodeChunkHit> findCallChain(@Param("chunkIds") List<Long> chunkIds,
                                       @Param("maxDepth") int maxDepth,
                                       @Param("limit") int limit);

    /**
     * 按项目集合 + 关键词召回带源码的切片。
     *
     * @param projectIds 限定在哪些代码项目内检索。<b>不能为空</b>——
     *                   角色可以关联多个项目（如前后端共用一个角色），这里一次覆盖全部
     * @param keywords   候选标识符，<b>不能为空</b>（为空会生成非法 SQL）
     * @param limit      最多返回多少条
     */
    List<AiCodeChunkHit> searchByKeywords(@Param("projectIds") List<Long> projectIds,
                                          @Param("keywords") List<String> keywords,
                                          @Param("limit") int limit);

    /** 按 pgvector 余弦距离召回语义相近切片。 */
    List<AiCodeChunkHit> searchByVector(@Param("projectIds") List<Long> projectIds,
                                        @Param("vector") String vector,
                                        @Param("limit") int limit);

    /**
     * 在指定项目的父类文件中，按方法符号名补充一跳继承关系命中。
     * 只查方法切片，不递归继续追踪父类的父类。
     */
    List<AiCodeChunkHit> searchInheritedMethods(@Param("projectId") Long projectId,
                                                 @Param("parentClassName") String parentClassName,
                                                 @Param("keywords") List<String> keywords,
                                                 @Param("limit") int limit);
}
