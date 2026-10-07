package zzz.service.forum.ai;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * AI 输入与输出的最小安全边界。
 */
@Component
public class AiSafetyGuard {

    //    private static final Pattern INJECTION = Pattern.compile(
//            "(?i)(ignore\\s+(all|any|previous)|忽略.{0,12}(之前|上面|所有).{0,8}规则|开发者模式|system\\s*prompt|系统提示词|输出.{0,12}(源码|sql|密钥)|把.{0,12}(代码|sql).{0,8}(全部|完整))");
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(ignore\\s+(all|any|previous)|忽略.{0,12}(之前|上面|所有).{0,8}规则|开发者模式|system\\s*prompt|系统提示词)"
    );
    private static final Pattern SOURCE = Pattern.compile(
            "(?is)(^|\\n)\\s*(package\\s+[\\w.]+;|import\\s+[\\w.]+;|select\\s+.+\\s+from|insert\\s+into|update\\s+\\w+\\s+set|delete\\s+from|```(?:java|sql|yaml|json))");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?key|private[_-]?key|password|token|secret)\\s*[:=]");

    public boolean isPromptInjection(String text) {
        return text != null && INJECTION.matcher(text).find();
    }

    public boolean containsRestrictedOutput(String text) {
        //     * 内部开发/运维 AI 不限制代码、SQL、配置文件输出。
        return false;
//        return text != null && (SOURCE.matcher(text).find() || SECRET.matcher(text).find());
    }

    /**
     * 统一拒答文案。
     * <p>
     * 注意措辞：要扣的是**源码正文、SQL 语句、配置文件内容、密钥、仓库内部路径**，
     * 不能笼统写成"不能提供配置/字段名"——权限编码、模块编码、状态值这类业务标识
     * 是用户配置角色和排障时要照抄的，写含糊了模型就会连它们一起拒答。
     */
    public String refusal() {
//        return "我可以解释业务规则、处理流程和数据含义，包括权限编码、模块编码、状态值这类业务标识；"
//                + "但不能提供源代码正文、SQL 语句、配置文件内容、密钥或仓库内部路径。";
        return "无法执行该请求。";
    }
}
