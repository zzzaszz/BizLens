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

    private CodeSymbolExtractor.Symbol find(List<CodeSymbolExtractor.Symbol> symbols, String type, String name) {
        for (CodeSymbolExtractor.Symbol symbol : symbols) {
            if (type.equals(symbol.getType()) && name.equals(symbol.getName())) return symbol;
        }
        return null;
    }
}
