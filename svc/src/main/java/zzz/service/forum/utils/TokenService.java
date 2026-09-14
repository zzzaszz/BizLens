package zzz.service.forum.utils;

import org.springframework.stereotype.Service;

@Service
public class TokenService {
    LoginUser loginUser;

    public LoginUser getLoginUser(String token) {
        return loginUser;
    }

    public void setLoginUser(LoginUser loginUser) {
        this.loginUser = loginUser;
    }
}
