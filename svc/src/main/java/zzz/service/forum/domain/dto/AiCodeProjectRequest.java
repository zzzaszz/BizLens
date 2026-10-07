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
    /** 私有仓库访问令牌（明文，仅入参；落库前加密）。编辑时留空表示不修改 */
    private String credentialToken;
    /** 令牌对应的用户名：GitHub 用 x-access-token、GitLab 用 oauth2，Gitee 需填账号名；留空按平台取默认 */
    private String credentialUser;
}
