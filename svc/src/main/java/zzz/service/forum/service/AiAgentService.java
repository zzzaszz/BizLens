package zzz.service.forum.service;

import zzz.service.forum.domain.AiAgentEntity;
import zzz.service.forum.domain.dto.AiAgentRequest;
import zzz.service.forum.domain.dto.AiModelListRequest;
import zzz.service.forum.domain.dto.AiModelListResponse;
import zzz.service.forum.domain.dto.PageResult;

import java.util.List;

/**
 * AI 角色服务接口
 */
public interface AiAgentService {

    AiAgentEntity getById(Long id);

    /** 项目下的全部角色（一个项目可以配置多个角色） */
    List<AiAgentEntity> listByProjectId(Long projectId);

    /** 当前用户创建的角色 */
    List<AiAgentEntity> listForUser(Long userId);

    PageResult<AiAgentEntity> pageForUser(Long userId, int page, int pageSize);

    /** 创建角色（必须绑定一个属于当前用户的代码项目） */
    AiAgentEntity create(Long userId, AiAgentRequest request);

    /** 编辑角色（校验归属，不允许改绑项目） */
    AiAgentEntity update(Long userId, AiAgentRequest request);

    /** 删除角色（校验归属） */
    boolean delete(Long userId, Long id);

    /**
     * 拉取厂商可用模型列表，用于填充配置表单的模型下拉。
     * 厂商不支持或调用失败时回退预置列表，不抛异常阻断表单；
     * 返回体里的 source 会标明数据是来自厂商还是预置，供前端提示用户。
     */
    AiModelListResponse listModels(Long userId, AiModelListRequest request);
}
