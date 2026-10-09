package zzz.service.forum.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CodeSymbolExtractorTest {

    private final CodeSymbolExtractor extractor = new CodeSymbolExtractor();

    @Test
    public void extractsJavaMethodsWithAnnotationsAndLineRange() {
        List<CodeSymbolExtractor.Symbol> symbols = extractor.extract("java",
                "class Demo {\n"
                        + "  @GetMapping(\"/items\")\n"
                        + "  public List<Item> listItems() {\n"
                        + "    return service.listItems();\n"
                        + "  }\n"
                        + "}\n");

        CodeSymbolExtractor.Symbol method = find(symbols, "METHOD", "listItems");
        assertNotNull(method);
        assertEquals(2, method.getStartLine());
        assertEquals(5, method.getEndLine());
        assertTrue(method.getSummary().contains("查询"));
        assertTrue(method.getSummary().contains("GET /items"));
        assertNotNull(find(symbols, "CLASS", "Demo"));
    }

    @Test
    public void extractsXmlMapperAndTableSymbols() {
        List<CodeSymbolExtractor.Symbol> symbols = extractor.extract("xml",
                "<select id=\"findItems\">\n"
                        + "  select * from biz_item join biz_shop on biz_shop.id = biz_item.shop_id\n"
                        + "</select>\n");

        CodeSymbolExtractor.Symbol mapper = find(symbols, "MAPPER", "findItems");
        assertNotNull(mapper);
        assertEquals(3, mapper.getEndLine());
        assertTrue(mapper.getSummary().contains("biz_item"));
        assertTrue(symbols.stream().anyMatch(s -> "TABLE".equals(s.getType())
                && "biz_item".equalsIgnoreCase(s.getName())));
        assertTrue(symbols.stream().anyMatch(s -> "TABLE".equals(s.getType())
                && "biz_shop".equalsIgnoreCase(s.getName())));
    }

    @Test
    public void extractsXmlMapperWithSingleQuotedId() {
        List<CodeSymbolExtractor.Symbol> symbols = extractor.extract("xml",
                "<mapper namespace=\"demo\">\n"
                        + "  <update id='updateItem'>\n"
                        + "    update biz_item set name = #{name}\n"
                        + "  </update>\n"
                        + "</mapper>\n");

        CodeSymbolExtractor.Symbol mapper = find(symbols, "MAPPER", "updateItem");
        assertNotNull(mapper);
        assertEquals(2, mapper.getStartLine());
        assertEquals(4, mapper.getEndLine());
        assertTrue(mapper.getSummary().contains("biz_item"));
    }

    @Test
    public void extractsVueScriptAndTemplateHandlers() {
        List<CodeSymbolExtractor.Symbol> symbols = extractor.extract("vue",
                "<template>\n"
                        + "  <button @click=\"submitOrder\">提交</button>\n"
                        + "</template>\n"
                        + "<script>\n"
                        + "export default {\n"
                        + "  methods: {\n"
                        + "    submitOrder() { return true }\n"
                        + "  }\n"
                        + "}\n"
                        + "</script>\n");

        CodeSymbolExtractor.Symbol method = find(symbols, "FUNCTION", "submitOrder");
        assertNotNull(method);
        assertEquals(7, method.getStartLine());
    }

    @Test
    public void regexFallbackHandlesGenericReturnTypesWithSpaces() {
        // 走与 Java 兜底同一段 JAVA_METHOD 正则（kt 分支直接命中该正则）。
        // 返回类型里含 ", " 的泛型曾经完全匹配不到，这类方法在索引里会整体消失——
        // 实测 D05MBIR3APSController.getOnlineDayLoad 因此查不到请求方式与参数。
        List<CodeSymbolExtractor.Symbol> symbols = extractor.extract("kt",
                "class Demo {\n"
                        + "  public R<Map<String, Object>> getOnlineDayLoad(@RequestBody JSONObject body) {\n"
                        + "    return ok();\n"
                        + "  }\n"
                        + "}\n");

        CodeSymbolExtractor.Symbol method = find(symbols, "METHOD", "getOnlineDayLoad");
        assertNotNull(method);
        assertEquals(2, method.getStartLine());
        assertEquals(4, method.getEndLine());
    }

    @Test
    public void regexFallbackIgnoresControlStatementsAsMethods() {
        // 放宽返回类型字符类后，`^[ \t]*` 会回溯让出缩进、缩进被当成类型，
        // 行首的 `if (...) {` 曾因此被识别成名为 `if` 的方法（实测一个 Controller 多出 7 个）。
        List<CodeSymbolExtractor.Symbol> symbols = extractor.extract("kt",
                "class Demo {\n"
                        + "  public R<String> run() {\n"
                        + "    if (ready == null) {\n"
                        + "      return null;\n"
                        + "    }\n"
                        + "    return ok();\n"
                        + "  }\n"
                        + "}\n");

        assertNotNull(find(symbols, "METHOD", "run"));
        assertTrue(symbols.stream().noneMatch(s -> "METHOD".equals(s.getType()) && "if".equals(s.getName())));
    }

    private CodeSymbolExtractor.Symbol find(List<CodeSymbolExtractor.Symbol> symbols, String type, String name) {
        for (CodeSymbolExtractor.Symbol symbol : symbols) {
            if (type.equals(symbol.getType()) && name.equals(symbol.getName())) return symbol;
        }
        return null;
    }
}
