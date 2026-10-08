package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class AiKnowledgeSourceRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String gitUrl;
    private String gitBranch;
    private String rootPath;
    private String credentialToken;
    private String credentialUser;
}
