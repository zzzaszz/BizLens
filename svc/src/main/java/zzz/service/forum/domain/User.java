package zzz.service.forum.domain;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {

    private Long id;

    private String deviceId;

    /**
     * 登录账号（唯一）
     */
    private String username;

    /**
     * 密码哈希（salt:hash）
     */
    private String passwordHash;

    private String nickname;

    private String avatar;

    /**
     * 主题颜色（用户自定义，持久化存储）
     */
    private String themeColor;

    private Integer status;

    /**
     * 角色：0 普通用户，1 管理员
     */
    private Integer role;

    private String lastLoginIp;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Integer deleted;
}
