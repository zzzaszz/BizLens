package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

/** 角色 ↔ 项目 关联行，用于一次性取出某用户全部角色的项目关联再在内存里分组。 */
@Data
public class AiAgentProjectRef implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long agentId;
    private Long projectId;
}
