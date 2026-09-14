package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.AiCodeChunkEntity;
import zzz.service.forum.domain.dto.AiCodeChunkHit;

import java.util.List;

@Mapper
public interface AiCodeChunkMapper {

    int insert(AiCodeChunkEntity entity);

    /**
     * 按项目 + 关键词召回带源码的切片。
     *
     * @param projectId 限定在哪个代码项目内检索
     * @param keywords  候选标识符，<b>不能为空</b>（为空会生成非法 SQL）
     * @param limit     最多返回多少条
     */
    List<AiCodeChunkHit> searchByKeywords(@Param("projectId") Long projectId,
                                          @Param("keywords") List<String> keywords,
                                          @Param("limit") int limit);
}
