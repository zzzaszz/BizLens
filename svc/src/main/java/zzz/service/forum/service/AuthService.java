package zzz.service.forum.service;

import zzz.service.forum.domain.dto.LoginRequest;
import zzz.service.forum.domain.dto.RefreshRequest;
import zzz.service.forum.domain.dto.RegisterRequest;
import zzz.service.forum.domain.vo.LoginResponse;

import javax.servlet.http.HttpServletRequest;

public interface AuthService {
    /**
     * 账号密码登录
     */
    LoginResponse login(LoginRequest request, HttpServletRequest httpServletRequest);

    /**
     * 账号密码注册
     */
    LoginResponse register(RegisterRequest request, HttpServletRequest httpServletRequest);

    LoginResponse refresh(RefreshRequest request);

    void logout(String accessToken, String refreshToken);
}
