package zzz.service.forum.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals(1, symbols.size());
        assertEquals("listItems", symbols.get(0).getName());
        assertEquals(2, symbols.get(0).getStartLine());
        assertEquals(5, symbols.get(0).getEndLine());
    }

    @Test
    public void extractsXmlMapperAndTableSymbols() {
        List<CodeSymbolExtractor.Symbol> symbols = extractor.extract("xml",
                "<select id=\"findItems\">\n"
                        + "  select * from biz_item join biz_shop on biz_shop.id = biz_item.shop_id\n"
                        + "</select>\n");

        assertTrue(symbols.stream().anyMatch(s -> "MAPPER".equals(s.getType())
                && "findItems".equals(s.getName()) && s.getEndLine() == 3));
        assertTrue(symbols.stream().anyMatch(s -> "TABLE".equals(s.getType())
                && "biz_item".equalsIgnoreCase(s.getName())));
        assertTrue(symbols.stream().anyMatch(s -> "TABLE".equals(s.getType())
                && "biz_shop".equalsIgnoreCase(s.getName())));
    }
}
