package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AiAgentKnowledgeRef implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long agentId;
    private Long knowledgeId;
}
