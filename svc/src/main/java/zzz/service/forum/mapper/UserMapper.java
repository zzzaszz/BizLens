package zzz.service.forum.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import zzz.service.forum.domain.User;
import zzz.service.forum.domain.pojo.UserPojo;

import java.util.List;

@Mapper
public interface UserMapper {

    User getById(Long id);

    User getByDeviceId(String deviceId);

    /**
     * 按账号查询（登录 / 注册唯一性校验）
     */
    User getByUsername(String username);

    int insert(User user);

    int update(User user);

    int delete(Long id);

    User selectById(Long id);

    // ============ 用户管理（后台） ============

    /**
     * 分页查询用户（支持账号/昵称模糊、状态、角色过滤）
     */
    List<UserPojo> findPageList(@Param("keyword") String keyword,
                                @Param("status") Integer status,
                                @Param("role") Integer role,
                                @Param("offset") int offset,
                                @Param("pageSize") int pageSize);

    /**
     * 用户总数（同过滤条件）
     */
    long countPageList(@Param("keyword") String keyword,
                       @Param("status") Integer status,
                       @Param("role") Integer role);

    /**
     * 更新账号状态（封禁/解封）
     */
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * 重置密码哈希
     */
    int updatePassword(@Param("id") Long id, @Param("passwordHash") String passwordHash);

    /**
     * 更新昵称/角色
     */
    int updateProfile(@Param("id") Long id, @Param("nickname") String nickname, @Param("role") Integer role);

    /**
     * 更新用户主题颜色
     */
    int updateThemeColor(@Param("id") Long id, @Param("themeColor") String themeColor);

    /**
     * 更新用户个人资料（昵称、签名、主题颜色）
     */
    int updateUserProfile(@Param("id") Long id,
                          @Param("nickname") String nickname,
                          @Param("themeColor") String themeColor);
}
