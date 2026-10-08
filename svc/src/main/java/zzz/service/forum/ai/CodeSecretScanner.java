package zzz.service.forum.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 代码入库前的轻量敏感信息扫描器。
 * <p>
 * 这是降低落库风险的第一道规则防线，不替代完整 Secret Scanner。命中后只把值替换为占位符，
 * 保留字段名和代码结构，便于业务检索；原始内容只在本次索引的内存中短暂存在。
 */
public class CodeSecretScanner {

    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?im)(\\b(?:password|passwd|token|api[_-]?key|secret|client[_-]?secret|private[_-]?key|access[_-]?key)\\b"
                    + "\\s*[:=]\\s*)(?!(?:\\[REDACTED(?:_TOKEN|_PRIVATE_KEY)?\\]))"
                    + "([\\\"']?)([^\\\"'\\s,;}\\r\\n]+)\\2");
    private static final Pattern KNOWN_TOKEN = Pattern.compile(
            "(?i)\\b(?:AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,})\\b");

    public ScanResult scan(String source) {
        if (source == null || source.isEmpty()) {
            return new ScanResult(source == null ? "" : source, Collections.<String>emptyList());
        }
        Set<String> rules = new LinkedHashSet<>();
        String sanitized = replacePrivateKeys(source, rules);
        sanitized = replaceKnownTokens(sanitized, rules);
        sanitized = replaceAssignments(sanitized, rules);
        return new ScanResult(sanitized, new ArrayList<>(rules));
    }

    private String replacePrivateKeys(String source, Set<String> rules) {
        Matcher matcher = PRIVATE_KEY.matcher(source);
        if (!matcher.find()) return source;
        rules.add("PRIVATE_KEY");
        return matcher.replaceAll("[REDACTED_PRIVATE_KEY]");
    }

    private String replaceAssignments(String source, Set<String> rules) {
        Matcher matcher = ASSIGNMENT.matcher(source);
        StringBuffer result = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            rules.add("SENSITIVE_ASSIGNMENT");
            String quote = matcher.group(2);
            matcher.appendReplacement(result, Matcher.quoteReplacement(
                    matcher.group(1) + quote + "[REDACTED]" + quote));
        }
        if (!found) return source;
        matcher.appendTail(result);
        return result.toString();
    }

    private String replaceKnownTokens(String source, Set<String> rules) {
        Matcher matcher = KNOWN_TOKEN.matcher(source);
        if (!matcher.find()) return source;
        rules.add("KNOWN_TOKEN");
        return matcher.replaceAll("[REDACTED_TOKEN]");
    }

    public static class ScanResult {
        private final String sanitizedText;
        private final List<String> rules;

        public ScanResult(String sanitizedText, List<String> rules) {
            this.sanitizedText = sanitizedText;
            this.rules = rules;
        }

        public String getSanitizedText() {
            return sanitizedText;
        }

        public List<String> getRules() {
            return rules;
        }

        public boolean isRedacted() {
            return !rules.isEmpty();
        }
    }
}
