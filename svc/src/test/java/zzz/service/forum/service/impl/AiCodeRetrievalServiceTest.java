package zzz.service.forum.service.impl;

import org.junit.jupiter.api.Test;
import zzz.service.forum.domain.dto.AiCodeChunkHit;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiCodeRetrievalService#buildContext} 的注入取舍。
 * <p>
 * 重点覆盖「FILE 切片会挤掉同文件方法体」这个已实测的缺陷：FILE 切片保留完整正文，
 * 经 1500 字符截断后往往只剩 import 区，必须让位给方法切片。
 */
public class AiCodeRetrievalServiceTest {

    /** FILE 切片独有：只在 import 区出现的文本 */
    private static final String FILE_ONLY_TEXT = "import org.springframework.web.bind.annotation.RequestMapping;";
    /** 方法切片独有：这才是能回答「请求方式与参数」的正文 */
    private static final String METHOD_BODY = "public R<Map<String, Object>> getOnlineDayLoad(@RequestBody JSONObject body) {";

    private final AiCodeRetrievalService service = new AiCodeRetrievalService();

    @Test
    public void fileChunkYieldsToSymbolChunkOfSameDocument() {
        // FILE 排在前面（真实召回的常见顺序：长片段同分优先），压制必须与顺序无关
        String context = service.buildContext(Arrays.asList(
                hit(1L, "FILE", "D05MBIR3APSController.java", FILE_ONLY_TEXT),
                hit(1L, "METHOD", "getOnlineDayLoad", METHOD_BODY)));

        assertNotNull(context);
        assertTrue(context.contains(METHOD_BODY), "同文件方法体应注入上下文");
        assertFalse(context.contains(FILE_ONLY_TEXT), "同文件 FILE 切片应让位，不应注入 import 区");
    }

    @Test
    public void fileChunkKeptWhenNoSymbolChunkForSameDocument() {
        String context = service.buildContext(Collections.singletonList(
                hit(2L, "FILE", "D05M05B1Controller.java", FILE_ONLY_TEXT)));

        assertNotNull(context);
        assertTrue(context.contains(FILE_ONLY_TEXT), "无方法切片时 FILE 切片仍应注入");
    }

    @Test
    public void fileChunkKeptWhenSymbolChunkHasNoSnippet() {
        // 符号名和行号在、源码片段为空（历史索引未重建）：不得据此压制 FILE 切片
        String context = service.buildContext(Arrays.asList(
                hit(3L, "FILE", "D05MBIR3APSController.java", FILE_ONLY_TEXT),
                hit(3L, "METHOD", "getOnlineDayLoad", null)));

        assertNotNull(context);
        assertTrue(context.contains(FILE_ONLY_TEXT), "方法切片无源码时不应压制 FILE 切片");
    }

    @Test
    public void fileChunkOfOtherDocumentIsNotSuppressed() {
        String context = service.buildContext(Arrays.asList(
                hit(4L, "FILE", "D05MBIR3APSController.java", FILE_ONLY_TEXT),
                hit(5L, "METHOD", "getOnlineDayLoad", METHOD_BODY)));

        assertNotNull(context);
        assertTrue(context.contains(FILE_ONLY_TEXT), "压制只对同文件的 FILE 切片生效");
        assertTrue(context.contains(METHOD_BODY));
    }

    @Test
    public void oversizedFileChunkDropsLeadingImportsSoMethodBodySurvives() {
        // 2000+ 字符的 import 区在前：若不丢弃，1500 字符预算会被 import 吃光，方法体注定看不到
        StringBuilder snippet = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            snippet.append("import org.springframework.beans.factory.annotation.Qualifier;\n");
        }
        snippet.append(METHOD_BODY);

        String context = service.buildContext(Collections.singletonList(
                hit(6L, "FILE", "D05MBIR3APSController.java", snippet.toString())));

        assertNotNull(context);
        assertTrue(context.contains(METHOD_BODY), "丢失 import 区后方法体应能进入上下文");
        assertTrue(context.contains("开头 package/import 声明已省略"), "应显式标注省略了 import 区");
    }

    @Test
    public void smallFileChunkKeepsImportsUntouched() {
        String context = service.buildContext(Collections.singletonList(
                hit(7L, "FILE", "D05MBIR3APSController.java", FILE_ONLY_TEXT + "\n" + METHOD_BODY)));

        assertNotNull(context);
        assertTrue(context.contains(FILE_ONLY_TEXT), "未超限的片段应原样注入，不丢 import");
        assertFalse(context.contains("已截断"), "未超限的片段不应显示截断标记");
    }

    private AiCodeChunkHit hit(Long documentId, String chunkType, String symbolName, String snippet) {
        AiCodeChunkHit hit = new AiCodeChunkHit();
        hit.setChunkId(documentId * 10 + (snippet == null ? 0 : 1));
        hit.setDocumentId(documentId);
        hit.setProjectId(1L);
        hit.setPath("inks-service-std-manu/src/main/java/inks/service/std/manu/controller/" + symbolName);
        hit.setLanguage("java");
        hit.setChunkType(chunkType);
        hit.setSymbolName(symbolName);
        hit.setBusinessSummary("摘要 " + symbolName);
        hit.setSourceSnippet(snippet);
        hit.setStartLine(1);
        hit.setEndLine(2);
        return hit;
    }
}
