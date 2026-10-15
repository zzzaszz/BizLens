package zzz.service.forum.domain.dto;

import lombok.Data;

@Data
public class RegisterRequest {

    /**
     * 登录账号
     */
    private String username;

    /**
     * 明文密码
     */
    private String password;

    /**
     * 昵称（可选，为空则系统生成）
     */
    private String nickname;

    /**
     * 设备标识（可选）
     */
    private String deviceId;

    /**
     * 邀请码（启用邀请码注册时必填）
     */
    private String inviteCode;
}
