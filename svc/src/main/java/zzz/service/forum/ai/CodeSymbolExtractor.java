package zzz.service.forum.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 不引入额外解析依赖的轻量符号提取器。
 * 只提取稳定的名称和行号，源码正文不会进入返回结果。
 */
public class CodeSymbolExtractor {

    private static final Pattern JAVA_METHOD = Pattern.compile(
            "(?m)^\\s*(?:(?:public|protected|private|static|final|synchronized|native|abstract|default)\\s+)*[\\w<>?,.\\[\\]]+\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[^\\{]+)?\\{");
    private static final Pattern XML_OPERATION = Pattern.compile(
            "(?m)^\\s*<(select|insert|update|delete)\\s+[^>]*\\bid\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern JS_FUNCTION = Pattern.compile(
            "(?m)^\\s*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)\\s*\\(");
    private static final Pattern JS_ARROW = Pattern.compile(
            "(?m)^\\s*(?:export\\s+)?(?:const|let)\\s+(\\w+)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*=>");

    public List<Symbol> extract(String language, String source) {
        List<Symbol> symbols = new ArrayList<>();
        if (source == null || source.isEmpty()) return symbols;
        if ("java".equals(language) || "kt".equals(language)) {
            collectMethods(symbols, JAVA_METHOD, source, "METHOD", "方法");
        } else if ("xml".equals(language)) {
            collectXmlOperations(symbols, source);
        } else if ("js".equals(language) || "ts".equals(language) || "vue".equals(language)) {
            collectNamed(symbols, JS_FUNCTION, source, "FUNCTION", "前端函数");
            collectNamed(symbols, JS_ARROW, source, "FUNCTION", "前端函数");
        }
        return symbols;
    }

    private void collectMethods(List<Symbol> target, Pattern pattern, String source, String type, String label) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int start = lineOf(source, matcher.start());
            int end = closingLine(source, matcher.end() - 1, start);
            String name = matcher.group(1);
            target.add(new Symbol(type, name, start, end, label + " " + name));
        }
    }

    private void collectNamed(List<Symbol> target, Pattern pattern, String source, String type, String label) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int line = lineOf(source, matcher.start());
            String name = matcher.group(1);
            target.add(new Symbol(type, name, line, line, label + " " + name));
        }
    }

    private void collectXmlOperations(List<Symbol> target, String source) {
        Matcher matcher = XML_OPERATION.matcher(source);
        while (matcher.find()) {
            int line = lineOf(source, matcher.start());
            String operation = matcher.group(1);
            String name = matcher.group(2);
            target.add(new Symbol("MAPPER", name, line, line, "Mapper " + operation + " " + name));
        }
    }

    private int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) if (source.charAt(i) == '\n') line++;
        return line;
    }

    private int closingLine(String source, int openBrace, int startLine) {
        int depth = 0;
        boolean opened = false;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') { depth++; opened = true; }
            if (current == '}') {
                depth--;
                if (opened && depth <= 0) return lineOf(source, i);
            }
        }
        return startLine;
    }

    public static class Symbol {
        private final String type;
        private final String name;
        private final int startLine;
        private final int endLine;
        private final String summary;

        public Symbol(String type, String name, int startLine, int endLine, String summary) {
            this.type = type;
            this.name = name;
            this.startLine = startLine;
            this.endLine = endLine;
            this.summary = summary;
        }

        public String getType() { return type; }
        public String getName() { return name; }
        public int getStartLine() { return startLine; }
        public int getEndLine() { return endLine; }
        public String getSummary() { return summary; }
    }
}
