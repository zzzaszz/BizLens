package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;

/** 创建或编辑代码项目请求。 */
@Data
public class AiCodeProjectRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String gitUrl;
    private String gitBranch;
    private String credentialRef;
}
