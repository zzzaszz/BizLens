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

    /*
     * 四个模式的行首都用 `^[ \t]*` 而不是 `^\s*`。
     *
     * `\s` 包含换行，所以 `^\s*` 会把**前一行的空行**一并吃进匹配里，
     * matcher.start() 于是落在空行上、记录的行号也跟着错位一格，
     * 最终取出的"源码片段"只有一个换行符。svc-sys 一个项目里就有 145 个这样的残片，
     * 它们长度最短，在召回排序（按片段长度升序）里排在最前面，把检索名额全占掉——
     * 实测表现为"问某接口的权限，模型说片段里找不到这个接口"。
     */
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|static|final|synchronized|native|abstract|default)\\s+)*[\\w<>?,.\\[\\]]+\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[^\\{]+)?\\{");
    private static final Pattern XML_OPERATION = Pattern.compile(
            "(?m)^[ \\t]*<(select|insert|update|delete)\\s+[^>]*\\bid\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern JS_FUNCTION = Pattern.compile(
            "(?m)^[ \\t]*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)\\s*\\(");
    private static final Pattern JS_ARROW = Pattern.compile(
            "(?m)^[ \\t]*(?:export\\s+)?(?:const|let)\\s+(\\w+)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*=>");
    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?i)\\b(?:from|join|update|into|table)\\s+([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)");

    public List<Symbol> extract(String language, String source) {
        List<Symbol> symbols = new ArrayList<>();
        if (source == null || source.isEmpty()) return symbols;
        String[] lines = source.split("\\R", -1);
        if ("java".equals(language) || "kt".equals(language)) {
            collectMethods(symbols, JAVA_METHOD, source, lines, "METHOD", "方法");
        } else if ("xml".equals(language)) {
            collectXmlOperations(symbols, source);
            collectSqlTables(symbols, source);
        } else if ("js".equals(language) || "ts".equals(language) || "vue".equals(language)) {
            collectNamed(symbols, JS_FUNCTION, source, "FUNCTION", "前端函数");
            collectNamed(symbols, JS_ARROW, source, "FUNCTION", "前端函数");
        } else if ("sql".equals(language)) {
            collectSqlTables(symbols, source);
        }
        return symbols;
    }

    private void collectSqlTables(List<Symbol> target, String source) {
        Matcher matcher = SQL_TABLE.matcher(source);
        while (matcher.find()) {
            int line = lineOf(source, matcher.start());
            String table = matcher.group(1);
            boolean exists = false;
            for (Symbol symbol : target) {
                if (symbol.getName().equalsIgnoreCase(table)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                target.add(new Symbol("TABLE", table, line, line, "SQL 表 " + table));
            }
        }
    }

    private void collectMethods(List<Symbol> target, Pattern pattern, String source, String[] lines,
                                String type, String label) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int start = includeLeadingAnnotations(lines, lineOf(source, matcher.start()));
            int end = closingLine(source, matcher.end() - 1, start);
            String name = matcher.group(1);
            target.add(new Symbol(type, name, start, end, label + " " + name));
        }
    }

    /**
     * 把签名上方的注解与注释行一并纳入切片起点。
     * <p>
     * 正则匹配的是"签名那一行"，直接拿它当起点会把上面的注解全丢掉——而
     * {@code @PreAuthorize} / {@code @RequestMapping} 这类注解恰恰是"这个接口要什么权限、
     * 怎么调"的答案所在。实测：问「SYSM03B4/getEntity 的接口权限」时，检索到的 getEntity
     * 切片里没有 {@code @PreAuthorize(hasPermi = ...)}，模型只能回答"看不到注解、无法确认"。
     * <p>
     * 向上收集直到遇见第一行"像代码"的内容：空行、以 {@code @} 开头的注解、注释行都算，
     * 遇到 {@code }}、{@code ;} 之类就停（那是上一段代码的结尾，不该被吃进来）。
     */
    private int includeLeadingAnnotations(String[] lines, int signatureLine) {
        int start = signatureLine;
        for (int i = signatureLine - 2; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("//")
                    || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                start = i + 1;
                continue;
            }
            break;
        }
        return start;
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
            int startLine = lineOf(source, matcher.start());
            String operation = matcher.group(1);
            String name = matcher.group(2);
            String closingTag = "</" + operation + ">";
            int closingOffset = source.indexOf(closingTag, matcher.end());
            int endLine = closingOffset >= 0 ? lineOf(source, closingOffset) : startLine;
            target.add(new Symbol("MAPPER", name, startLine, endLine, "Mapper " + operation + " " + name));
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
