package zzz.service.forum.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型下拉的预置兜底列表，绑定 {@code ai.preset-models.*}。
 * <p>
 * 只在厂商 {@code /models} 调用失败时使用（无 Key / Key 失效 / 网关不通）。
 * 模型名迭代很快（例如 deepseek-chat 别名已于 2026-07-24 下线），
 * 因此放在配置里而不是硬编码，改名不必重新编译。
 */
@Component
@ConfigurationProperties(prefix = "ai")
public class AiModelPresets {

    /** 厂商标识 → 预置模型 ID 列表 */
    private Map<String, List<String>> presetModels = new LinkedHashMap<>();

    public Map<String, List<String>> getPresetModels() {
        return presetModels;
    }

    public void setPresetModels(Map<String, List<String>> presetModels) {
        this.presetModels = presetModels;
    }
}
