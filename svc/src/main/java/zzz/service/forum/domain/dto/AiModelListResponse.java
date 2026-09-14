package zzz.service.forum.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 模型列表响应。
 * <p>
 * 除了列表本身，还要带上 {@link #source}：前端据此提示用户"这是厂商返回的真实列表"
 * 还是"厂商没调通、用的本地预置兜底"。否则兜底是静默的，用户会以为下拉里就是当前可用模型。
 */
@Data
public class AiModelListResponse implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 模型 ID 列表 */
    private List<String> models = new ArrayList<>();

    /** 数据来源，见下面的常量 */
    private String source;

    /** 兜底或失败时的说明，便于用户排查为什么没拿到真实列表 */
    private String message;

    /** 来自厂商 /models 接口，是当前真实可用的模型 */
    public static final String SOURCE_PROVIDER = "PROVIDER";
    /** 厂商接口没调通，展示的是本地预置列表，需要用户自行确认 */
    public static final String SOURCE_PRESET = "PRESET";
    /** 既没拿到厂商列表，也没有对应厂商的预置列表 */
    public static final String SOURCE_EMPTY = "EMPTY";
}
