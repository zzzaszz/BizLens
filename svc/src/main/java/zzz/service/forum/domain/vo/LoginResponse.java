package zzz.service.forum.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String accessToken;

    private String refreshToken;

    /** Backward compat: frontend reads data.token */
    private String token;

    private Long userId;

    private String nickname;

    private String avatar;

    /**
     * 角色：0 普通用户，1 管理员
     */
    private Integer role;

    /**
     * 用户自定义主题颜色
     */
    private String themeColor;
}
