package zzz.service.forum.domain.dto;

import lombok.Data;

@Data
public class LoginRequest {

    /**
     * 登录账号
     */
    private String username;

    /**
     * 明文密码
     */
    private String password;

    /**
     * 设备标识（可选，风控/埋点用）
     */
    private String deviceId;

    /**
     * 图形验证码标识（达到失败阈值后必填）
     */
    private String captchaId;

    /**
     * 图形验证码答案（达到失败阈值后必填）
     */
    private String captchaCode;
}
