package zzz.service.forum.utils;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonProperty.Access;

import java.util.Map;
import java.util.Set;

public class LoginUser {
    private String token;
    private String tenantid;
    @JsonProperty(
            access = Access.WRITE_ONLY
    )
    private String password;
    private String username;
    private String userid;
    private String realname;
    private String avatar;
    private Long logintime;
    private Long expiretime;
    private Integer isadmin;
    private String ipaddr;
    private String groupids;
    private Set<String> permissions;
    private Map<String, String> configs;
    private String logid;
    private String hostsystem;
    private Integer isregister;
    private String datalabel;

    public String getToken() {
        return this.token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTenantid() {
        return this.tenantid;
    }

    public void setTenantid(String tenantid) {
        this.tenantid = tenantid;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUserid() {
        return this.userid;
    }

    public void setUserid(String userid) {
        this.userid = userid;
    }

    public String getRealname() {
        return this.realname;
    }

    public String getRealName() {
        return this.realname;
    }

    public void setRealname(String realname) {
        this.realname = realname;
    }

    public Long getLogintime() {
        return this.logintime;
    }

    public void setLogintime(Long logintime) {
        this.logintime = logintime;
    }

    public Long getExpiretime() {
        return this.expiretime;
    }

    public void setExpiretime(Long expiretime) {
        this.expiretime = expiretime;
    }

    public String getAvatar() {
        return this.avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public Integer getIsadmin() {
        return this.isadmin;
    }

    public void setIsadmin(Integer isadmin) {
        this.isadmin = isadmin;
    }

    public String getIpaddr() {
        return this.ipaddr;
    }

    public void setIpaddr(String ipaddr) {
        this.ipaddr = ipaddr;
    }

    public Set<String> getPermissions() {
        return this.permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }

    public Map<String, String> getConfigs() {
        return this.configs;
    }

    public void setConfigs(Map<String, String> configs) {
        this.configs = configs;
    }

    public String getGroupids() {
        return this.groupids;
    }

    public void setGroupids(String groupids) {
        this.groupids = groupids;
    }

    public String getLogid() {
        return this.logid;
    }

    public void setLogid(String logid) {
        this.logid = logid;
    }

    public String getHostsystem() {
        return this.hostsystem;
    }

    public void setHostsystem(String hostsystem) {
        this.hostsystem = hostsystem;
    }

    public Integer getIsregister() {
        return this.isregister;
    }

    public void setIsregister(Integer isregister) {
        this.isregister = isregister;
    }

    public String getDatalabel() {
        return this.datalabel;
    }

    public void setDatalabel(String datalabel) {
        this.datalabel = datalabel;
    }
}
