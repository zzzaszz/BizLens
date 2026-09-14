package zzz.service.forum.context;

public class UserContext {

    private static final ThreadLocal<Long> USER = new ThreadLocal<>();

    public static void setUserId(Long userId) {
        USER.set(userId);
    }

    public static Long getUserId() {
        return USER.get();
    }

    public static void clear() {
        USER.remove();
    }
}
