package zzz.service.forum.ai;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码结构提取器。
 * <p>
 * Java 优先使用 JavaParser AST，Mapper XML 优先使用安全 DOM，Vue 按 SFC 区块解析；解析失败时回退到保守规则。
 * 返回结果只包含稳定的名称、类型和行号，源码正文不会进入返回结果。
 */
public class CodeSymbolExtractor {

    private static final JavaParser JAVA_PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8));

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
            "(?m)^[ \\t]*<(select|insert|update|delete)\\s+[^>]*\\bid\\s*=\\s*(['\"])([^'\"]+)\\2");
    private static final Pattern JS_FUNCTION = Pattern.compile(
            "(?m)^[ \\t]*(?:export\\s+)?(?:async\\s+)?function\\s+(\\w+)\\s*\\(");
    private static final Pattern JS_ARROW = Pattern.compile(
            "(?m)^[ \\t]*(?:export\\s+)?(?:const|let)\\s+(\\w+)\\s*=\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*=>");
    private static final Pattern JS_OBJECT_METHOD = Pattern.compile(
            "(?m)^[ \\t]*(\\w+)\\s*\\([^)]*\\)\\s*\\{");
    private static final Pattern ROUTE_ANNOTATION = Pattern.compile(
            "@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern VUE_SCRIPT_BLOCK = Pattern.compile("(?is)<script\\b[^>]*>(.*?)</script>");
    private static final Pattern VUE_TEMPLATE_HANDLER = Pattern.compile(
            "(?i)@(?:click|change|submit|input|keyup|keydown|blur|focus)\\s*=\\s*[\\\"']([A-Za-z_$][\\w$]*)[\\\"']");
    private static final Pattern SQL_TABLE = Pattern.compile(
            "(?i)\\b(?:from|join|update|into|table)\\s+([A-Za-z_][\\w$]*(?:\\.[A-Za-z_][\\w$]*)?)");

    public List<Symbol> extract(String language, String source) {
        List<Symbol> symbols = new ArrayList<>();
        if (source == null || source.isEmpty()) return symbols;
        String[] lines = source.split("\\R", -1);
        if ("java".equals(language) || "kt".equals(language)) {
            if ("java".equals(language) && !collectJavaAst(symbols, source, lines)) {
                collectMethods(symbols, JAVA_METHOD, source, lines, "METHOD", "方法");
            } else if ("kt".equals(language)) {
                collectMethods(symbols, JAVA_METHOD, source, lines, "METHOD", "方法");
            }
        } else if ("xml".equals(language)) {
            if (!collectXmlOperationsByDom(symbols, source)) {
                collectXmlOperationsByPattern(symbols, source);
            }
            collectSqlTables(symbols, source);
        } else if ("vue".equals(language)) {
            collectVueSymbols(symbols, source);
        } else if ("js".equals(language) || "ts".equals(language) || "vue".equals(language)) {
            collectJavascriptSymbols(symbols, source, 0);
        } else if ("sql".equals(language)) {
            collectSqlTables(symbols, source);
        }
        return symbols;
    }

    private boolean collectJavaAst(final List<Symbol> target, final String source, final String[] lines) {
        try {
            ParseResult<CompilationUnit> result = JAVA_PARSER.parse(source);
            if (!result.isSuccessful() || !result.getResult().isPresent()) return false;
            CompilationUnit unit = result.getResult().get();
            for (ClassOrInterfaceDeclaration type : unit.findAll(ClassOrInterfaceDeclaration.class)) {
                int startLine = type.getBegin().isPresent() ? type.getBegin().get().line : 1;
                int endLine = type.getEnd().isPresent() ? type.getEnd().get().line : startLine;
                addSymbolIfAbsent(target, new Symbol("CLASS", type.getNameAsString(), startLine, endLine,
                        "Java 类型 " + type.getNameAsString()));
            }
            for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) {
                int signatureLine = method.getBegin().isPresent() ? method.getBegin().get().line : 1;
                int startLine = includeLeadingAnnotations(lines, signatureLine, annotations(method.getAnnotations()));
                int endLine = method.getEnd().isPresent() ? method.getEnd().get().line : startLine;
                addSymbolIfAbsent(target, new Symbol("METHOD", method.getNameAsString(), startLine, endLine,
                        buildMethodSummary(method.getNameAsString(), lines, startLine, signatureLine, "方法")));
            }
            for (ConstructorDeclaration constructor : unit.findAll(ConstructorDeclaration.class)) {
                int signatureLine = constructor.getBegin().isPresent() ? constructor.getBegin().get().line : 1;
                int startLine = includeLeadingAnnotations(lines, signatureLine, annotations(constructor.getAnnotations()));
                int endLine = constructor.getEnd().isPresent() ? constructor.getEnd().get().line : startLine;
                addSymbolIfAbsent(target, new Symbol("METHOD", constructor.getNameAsString(), startLine, endLine,
                        buildMethodSummary(constructor.getNameAsString(), lines, startLine, signatureLine, "构造方法")));
            }
            return !target.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private List<String> annotations(List<AnnotationExpr> annotations) {
        List<String> names = new ArrayList<>();
        for (AnnotationExpr annotation : annotations) names.add(annotation.getNameAsString());
        return names;
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
            int signatureLine = lineOf(source, matcher.start());
            int start = includeLeadingAnnotations(lines, signatureLine);
            int end = closingLine(source, matcher.end() - 1, start);
            String name = matcher.group(1);
            target.add(new Symbol(type, name, start, end,
                    buildMethodSummary(name, lines, start, signatureLine, label)));
        }
    }

    /** 生成不依赖模型调用的基础摘要，避免把源码正文写入元数据。 */
    private String buildMethodSummary(String name, String[] lines, int startLine, int signatureLine, String label) {
        StringBuilder summary = new StringBuilder(label).append(' ').append(name)
                .append("，动作：").append(inferAction(name));
        String route = routeFromAnnotations(lines, startLine, signatureLine);
        if (route != null) summary.append("，路由：").append(route);
        return summary.toString();
    }

    private String routeFromAnnotations(String[] lines, int startLine, int signatureLine) {
        for (int i = Math.max(0, startLine - 1); i < Math.min(lines.length, signatureLine); i++) {
            Matcher matcher = ROUTE_ANNOTATION.matcher(lines[i]);
            if (matcher.find()) return matcher.group(1).toUpperCase(Locale.ROOT) + " " + matcher.group(2);
        }
        return null;
    }

    private String inferAction(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "find", "query", "get", "list", "load", "search", "select")) return "查询";
        if (containsAny(normalized, "create", "add", "save", "insert", "register")) return "创建";
        if (containsAny(normalized, "update", "edit", "modify", "set")) return "更新";
        if (containsAny(normalized, "delete", "remove", "clear")) return "删除";
        if (containsAny(normalized, "export", "download")) return "导出";
        if (containsAny(normalized, "import", "upload")) return "导入";
        if (containsAny(normalized, "validate", "check", "verify")) return "校验";
        if (containsAny(normalized, "count", "stat")) return "统计";
        return "处理";
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) {
            if (value.startsWith(candidate) || value.contains("_" + candidate)
                    || value.contains("-" + candidate)) return true;
        }
        return false;
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
        return includeLeadingAnnotations(lines, signatureLine, Collections.<String>emptyList());
    }

    private int includeLeadingAnnotations(String[] lines, int signatureLine, List<String> knownAnnotations) {
        int start = signatureLine;
        for (int i = signatureLine - 2; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("@") || isKnownAnnotation(trimmed, knownAnnotations)
                    || trimmed.startsWith("//")
                    || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                start = i + 1;
                continue;
            }
            break;
        }
        return start;
    }

    private boolean isKnownAnnotation(String line, List<String> annotations) {
        if (annotations == null || annotations.isEmpty()) return false;
        for (String annotation : annotations) {
            if (line.contains("@" + annotation)) return true;
        }
        return false;
    }

    private void collectJavascriptSymbols(List<Symbol> target, String source, int lineOffset) {
        collectNamed(target, JS_FUNCTION, source, "FUNCTION", "前端函数", lineOffset);
        collectNamed(target, JS_ARROW, source, "FUNCTION", "前端函数", lineOffset);
        collectVueOptionsMethods(target, source, lineOffset);
    }

    private void collectVueSymbols(List<Symbol> target, String source) {
        Matcher scriptMatcher = VUE_SCRIPT_BLOCK.matcher(source);
        boolean foundScript = false;
        while (scriptMatcher.find()) {
            foundScript = true;
            collectJavascriptSymbols(target, scriptMatcher.group(1), lineOf(source, scriptMatcher.start(1)) - 1);
        }
        if (!foundScript) collectJavascriptSymbols(target, source, 0);

        Matcher handlerMatcher = VUE_TEMPLATE_HANDLER.matcher(source);
        while (handlerMatcher.find()) {
            String name = handlerMatcher.group(1);
            addSymbolIfAbsent(target, new Symbol("FUNCTION", name, lineOf(source, handlerMatcher.start(1)),
                    lineOf(source, handlerMatcher.start(1)), "Vue 模板事件 " + name));
        }
    }

    private void collectVueOptionsMethods(List<Symbol> target, String source, int lineOffset) {
        int methodsIndex = source.indexOf("methods");
        while (methodsIndex >= 0) {
            int colon = source.indexOf(':', methodsIndex);
            int open = colon >= 0 ? source.indexOf('{', colon) : -1;
            int close = open >= 0 ? closingBraceOffset(source, open) : -1;
            if (open >= 0 && close > open) {
                String body = source.substring(open + 1, close);
                collectNamed(target, JS_OBJECT_METHOD, body, "FUNCTION", "Vue 方法", lineOffset + lineOf(source, open) - 1);
            }
            methodsIndex = source.indexOf("methods", methodsIndex + 7);
        }
    }

    private void collectNamed(List<Symbol> target, Pattern pattern, String source, String type, String label,
                              int lineOffset) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            int line = lineOffset + lineOf(source, matcher.start());
            String name = matcher.group(1);
            addSymbolIfAbsent(target, new Symbol(type, name, line, line, label + " " + name));
        }
    }

    private boolean collectXmlOperationsByDom(List<Symbol> target, String source) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(source)));
            String[] operations = {"select", "insert", "update", "delete"};
            for (String operation : operations) {
                NodeList nodes = document.getElementsByTagName(operation);
                for (int i = 0; i < nodes.getLength(); i++) {
                    Element element = (Element) nodes.item(i);
                    String name = element.getAttribute("id");
                    if (name == null || name.trim().isEmpty()) continue;
                    int startLine = locateXmlStartLine(source, operation, name);
                    List<String> tables = sqlTables(element.getTextContent());
                    String summary = "Mapper " + operation + " " + name
                            + (tables.isEmpty() ? "" : "，表：" + join(tables));
                    addSymbolIfAbsent(target, new Symbol("MAPPER", name, startLine,
                            locateXmlEndLine(source, operation, startLine), summary));
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void collectXmlOperationsByPattern(List<Symbol> target, String source) {
        Matcher matcher = XML_OPERATION.matcher(source);
        while (matcher.find()) {
            int startLine = lineOf(source, matcher.start());
            String operation = matcher.group(1);
            String name = matcher.group(3);
            String closingTag = "</" + operation + ">";
            int closingOffset = source.indexOf(closingTag, matcher.end());
            int endLine = closingOffset >= 0 ? lineOf(source, closingOffset) : startLine;
            addSymbolIfAbsent(target, new Symbol("MAPPER", name, startLine, endLine, "Mapper " + operation + " " + name));
        }
    }

    private int locateXmlStartLine(String source, String operation, String id) {
        Pattern pattern = Pattern.compile("(?is)<" + operation + "\\b[^>]*\\bid\\s*=\\s*(['\\\"])"
                + Pattern.quote(id) + "\\1");
        Matcher matcher = pattern.matcher(source);
        return matcher.find() ? lineOf(source, matcher.start()) : 1;
    }

    private int locateXmlEndLine(String source, String operation, int startLine) {
        String closingTag = "</" + operation + ">";
        int from = offsetOfLine(source, startLine);
        int close = source.indexOf(closingTag, Math.max(0, from));
        return close >= 0 ? lineOf(source, close) : startLine;
    }

    private List<String> sqlTables(String source) {
        List<String> tables = new ArrayList<>();
        Matcher matcher = SQL_TABLE.matcher(source == null ? "" : source);
        while (matcher.find()) {
            String table = matcher.group(1);
            boolean exists = false;
            for (String existing : tables) {
                if (existing.equalsIgnoreCase(table)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) tables.add(table);
        }
        return tables;
    }

    private String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(',');
            builder.append(value);
        }
        return builder.toString();
    }

    private void addSymbolIfAbsent(List<Symbol> target, Symbol symbol) {
        for (Symbol existing : target) {
            if (existing.getType().equals(symbol.getType()) && existing.getName().equals(symbol.getName())
                    && existing.getStartLine() == symbol.getStartLine()) return;
        }
        target.add(symbol);
    }

    private int lineOf(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) if (source.charAt(i) == '\n') line++;
        return line;
    }

    private int offsetOfLine(String source, int line) {
        if (line <= 1) return 0;
        int currentLine = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n' && ++currentLine == line) return i + 1;
        }
        return source.length();
    }

    private int closingBraceOffset(String source, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '{') depth++;
            if (current == '}' && --depth <= 0) return i;
        }
        return -1;
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
