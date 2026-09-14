package zzz.service.forum.domain.pojo;

import java.io.Serializable;
import java.util.Date;

/**
 * 用户管理视图对象（不含密码哈希）。
 *
 * @author caoshi
 * @since 2026-06-30
 */
public class UserPojo implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    /**
     * 1正常 2封禁 3注销
     */
    private Integer status;
    /**
     * 0普通 1管理员
     */
    private Integer role;
    private String lastloginip;
    private Date lastloginat;
    private Date createdat;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getRole() {
        return role;
    }

    public void setRole(Integer role) {
        this.role = role;
    }

    public String getLastloginip() {
        return lastloginip;
    }

    public void setLastloginip(String lastloginip) {
        this.lastloginip = lastloginip;
    }

    public Date getLastloginat() {
        return lastloginat;
    }

    public void setLastloginat(Date lastloginat) {
        this.lastloginat = lastloginat;
    }

    public Date getCreatedat() {
        return createdat;
    }

    public void setCreatedat(Date createdat) {
        this.createdat = createdat;
    }
}
