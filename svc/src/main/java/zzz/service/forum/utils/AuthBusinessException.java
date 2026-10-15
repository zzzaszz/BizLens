package zzz.service.forum.utils;

/**
 * 登录相关业务异常。
 *
 * <p>认证接口需要在保持现有 R 响应结构的前提下返回业务码和附加数据，
 * 例如验证码触发时的 failCount。普通 RuntimeException 不携带这些信息。</p>
 */
public class AuthBusinessException extends RuntimeException {

    private final int code;
    private final Object data;

    public AuthBusinessException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int getCode() {
        return code;
    }

    public Object getData() {
        return data;
    }
}
