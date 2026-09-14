package zzz.service.forum.config;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * SPA 路由回退控制器
 * 将所有未匹配的前端路由转发到 index.html，解决 Vue Router history 模式刷新 404 的问题
 */
@Controller
public class SpaErrorController implements ErrorController {

    @RequestMapping("/error")
    public String handleError(HttpServletRequest request, HttpServletResponse response) {
        int status = response.getStatus();
        // 非静态资源请求且非 API 请求，转发到 index.html
        String uri = request.getRequestURI();
        if (status == 404
                && !uri.startsWith("/api/")
                && !uri.startsWith("/webjars/")
                && !uri.startsWith("/swagger")
                && !uri.startsWith("/v2/")
                && !uri.startsWith("/doc.html")
                && !uri.contains(".")) {
            return "forward:/index.html";
        }
        return null;
    }
}
