package zzz.service.forum.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zzz.service.forum.domain.dto.R;
import zzz.service.forum.utils.BaseBusinessException;

/**
 * 全局异常兜底。
 * <p>
 * 没有它的时候，任何一个忘了写 try/catch 的接口抛出异常，都会走 /error 变成
 * **空 body 的 HTTP 500**——前端 `res.code` 拿到 undefined，只能弹一句没有信息量的「请求失败」。
 * 项目里各 Controller 已经是「catch 住后返回 R.fail」的写法，这里只是把这个约定兜到全局，
 * 保证任何接口的异常都以同一形状返回。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：message 是给用户看的，原样返回 */
    @ExceptionHandler(BaseBusinessException.class)
    public R<Void> handleBusiness(BaseBusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return R.fail(e.getMessage());
    }

    /**
     * 其它异常：只记日志，对外给统一文案。
     * 不把异常信息回传给客户端——底层报错常带 SQL、路径、连接串等内部细节。
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e) {
        log.error("接口异常", e);
        return R.fail("服务异常，请稍后重试");
    }
}
