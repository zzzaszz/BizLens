package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import zzz.service.forum.domain.AiCodeChunkEntity;

@Mapper
public interface AiCodeChunkMapper {
    int insert(AiCodeChunkEntity entity);
}
