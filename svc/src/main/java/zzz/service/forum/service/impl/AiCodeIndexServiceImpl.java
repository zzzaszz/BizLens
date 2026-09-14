package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import zzz.service.forum.domain.AiCodeChunkEntity;
import zzz.service.forum.domain.AiCodeDocumentEntity;
import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.ai.CodeSymbolExtractor;
import zzz.service.forum.mapper.AiCodeChunkMapper;
import zzz.service.forum.mapper.AiCodeDocumentMapper;
import zzz.service.forum.mapper.AiCodeProjectMapper;
import zzz.service.forum.service.AiCodeIndexService;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 基础代码索引器：临时浅克隆、保存元数据与切片源码、删除工作区。
 * <p>
 * 每个切片会落一份截断后的源码原文（source_snippet），供对话时按关键词召回、
 * 注入模型理解业务逻辑；回答侧由 AiSafetyGuard 拦截源码输出。
 * 仍不执行 AST/Embedding，不做向量化。
 */
@Service("aiCodeIndexService")
public class AiCodeIndexServiceImpl implements AiCodeIndexService {

    private static final Logger log = LoggerFactory.getLogger(AiCodeIndexServiceImpl.class);
    private static final int MAX_FILES = 2000;
    private static final long MAX_FILE_BYTES = 2 * 1024 * 1024L;
    private static final int MAX_SYMBOLS_PER_FILE = 200;
    /** 单个切片的源码片段上限，防止一个巨型方法把库撑爆 */
    private static final int MAX_SNIPPET_CHARS = 1200;
    /** 每个文件所有切片的源码总量上限，兜住 MAX_SYMBOLS_PER_FILE 带来的最坏情况 */
    private static final int MAX_SNIPPET_CHARS_PER_FILE = 20000;
    /** FILE 级切片只留文件头，它主要用来提供"这个文件大概是什么"，不需要全量 */
    private static final int MAX_FILE_HEAD_CHARS = 800;
    private static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList(
            "java", "kt", "xml", "vue", "js", "ts", "yml", "yaml", "json", "sql"
    ));

    @Resource
    private AiCodeProjectMapper projectMapper;
    @Resource
    private AiCodeDocumentMapper documentMapper;
    @Resource
    private AiCodeChunkMapper chunkMapper;
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    private final CodeSymbolExtractor symbolExtractor = new CodeSymbolExtractor();

    @Override
    @Async
    public void indexAsync(Long projectId) {
        Path workspace = null;
        try {
            AiCodeProjectEntity project = projectMapper.getById(projectId);
            if (project == null) return;
            projectMapper.updateStatus(projectId, "INDEXING");
            validateGitUrl(project.getGitUrl());
            workspace = Files.createTempDirectory("bizlens-index-");
            runGitClone(project, workspace);
            String commit = runGitCommand(workspace, "rev-parse", "HEAD").trim();
            documentMapper.deleteByProject(projectId);
            IndexCounter counter = new IndexCounter();
            final Path root = workspace;
            Files.walkFileTree(workspace, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (counter.count >= MAX_FILES || attrs.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                    if (isIndexable(file) && !isIgnored(root, file)) {
                        try {
                            indexFile(projectId, root, file, attrs.size());
                            counter.count++;
                        } catch (Exception e) {
                            log.warn("skip code file {}: {}", file, e.getMessage());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            projectMapper.markIndexed(projectId, "READY", commit);
            log.info("code project indexed, projectId={}, files={}, commit={}", projectId, counter.count, commit);
        } catch (Exception e) {
            log.warn("code project index failed, projectId={}", projectId, e);
            // 同时写库：前端只看到一个「失败」标签是不够的，要能看到为什么失败
            projectMapper.markFailed(projectId, sanitizeGitError(e));
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private void indexFile(Long projectId, Path root, Path file, long bytes) throws Exception {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String relativePath = root.relativize(file).toString().replace('\\', '/');
        String language = extension(file);
        String checksum = sha256(text);
        Date now = new Date();
        // 按行切一次，后面所有片段都从这里取，避免反复 split
        String[] lines = text.split("\\R", -1);
        SnippetBudget budget = new SnippetBudget(MAX_SNIPPET_CHARS_PER_FILE);

        AiCodeDocumentEntity document = new AiCodeDocumentEntity();
        document.setId(snowflakeIdGenerator.nextId());
        document.setProjectId(projectId);
        document.setPath(relativePath);
        document.setLanguage(language);
        document.setChecksum(checksum);
        document.setSymbolName(file.getFileName().toString());
        document.setSummary("代码文件 " + relativePath + "，语言 " + language + "，大小 " + bytes + " 字节");
        document.setStartLine(1);
        document.setEndLine(lines.length);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentMapper.insert(document);

        AiCodeChunkEntity chunk = new AiCodeChunkEntity();
        chunk.setId(snowflakeIdGenerator.nextId());
        chunk.setDocumentId(document.getId());
        chunk.setChunkType("FILE");
        chunk.setSymbolName(document.getSymbolName());
        chunk.setBusinessSummary(document.getSummary());
        chunk.setSourceSnippet(sliceLines(lines, 1, lines.length, MAX_FILE_HEAD_CHARS));
        chunk.setMetadata("{\"path\":\"" + jsonEscape(relativePath) + "\",\"language\":\"" + jsonEscape(language) + "\",\"startLine\":1,\"endLine\":" + document.getEndLine() + "}");
        chunk.setCreatedAt(now);
        chunkMapper.insert(chunk);

        int symbolCount = 0;
        for (CodeSymbolExtractor.Symbol symbol : symbolExtractor.extract(language, text)) {
            if (symbolCount++ >= MAX_SYMBOLS_PER_FILE) break;
            if (!budget.tryConsume(MAX_SNIPPET_CHARS)) break;
            AiCodeChunkEntity symbolChunk = new AiCodeChunkEntity();
            symbolChunk.setId(snowflakeIdGenerator.nextId());
            symbolChunk.setDocumentId(document.getId());
            symbolChunk.setChunkType(symbol.getType());
            symbolChunk.setSymbolName(symbol.getName());
            symbolChunk.setBusinessSummary(symbol.getSummary() + "，文件 " + relativePath);
            symbolChunk.setSourceSnippet(sliceLines(lines, symbol.getStartLine(), symbol.getEndLine(), MAX_SNIPPET_CHARS));
            symbolChunk.setMetadata("{\"path\":\"" + jsonEscape(relativePath) + "\",\"language\":\"" + jsonEscape(language) + "\",\"startLine\":" + symbol.getStartLine() + ",\"endLine\":" + symbol.getEndLine() + "}");
            symbolChunk.setCreatedAt(now);
            chunkMapper.insert(symbolChunk);
        }
    }

    /**
     * 取 [startLine, endLine] 闭区间的源码文本，超长按 maxChars 截断。
     * 行号越界时返回空串而不是抛错——索引是个尽力而为的过程，不该为单个切片中断整轮索引。
     */
    private String sliceLines(String[] lines, int startLine, int endLine, int maxChars) {
        if (lines.length == 0) return "";
        int from = Math.max(startLine, 1);
        int to = Math.min(endLine, lines.length);
        if (from > to) return "";

        StringBuilder snippet = new StringBuilder(Math.min(maxChars, 256));
        for (int i = from; i <= to && snippet.length() < maxChars; i++) {
            snippet.append(lines[i - 1]).append('\n');
        }
        if (snippet.length() > maxChars) {
            snippet.setLength(maxChars);
            snippet.append("\n...(已截断)");
        }
        return snippet.toString();
    }

    private void runGitClone(AiCodeProjectEntity project, Path workspace) throws Exception {
        runCommand(workspace.getParent(), "git", "clone", "--depth", "1", "--branch",
                project.getGitBranch(), project.getGitUrl(), workspace.toString());
    }

    private String runGitCommand(Path directory, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        return runCommand(directory, command);
    }

    private String runCommand(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        boolean done = process.waitFor(120, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
        }
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(readAll(input), StandardCharsets.UTF_8);
        }
        if (!done || process.exitValue() != 0) {
            throw new IOException("Git 命令执行失败: " + truncate(output));
        }
        return output;
    }

    private byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private void validateGitUrl(String gitUrl) {
        if (gitUrl == null || !(gitUrl.startsWith("https://") || gitUrl.startsWith("http://") || gitUrl.startsWith("ssh://") || gitUrl.matches("^[^/@\\s]+@[^:]+:.+"))) {
            throw new IllegalArgumentException("仅支持 HTTP(S) 或 SSH Git 地址");
        }
    }

    private boolean isIndexable(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1));
    }

    private boolean isIgnored(Path root, Path file) {
        for (Path part : root.relativize(file)) {
            String name = part.toString();
            if (name.equals(".git") || name.equals("node_modules") || name.equals("target") || name.equals("dist")) return true;
        }
        return false;
    }

    private String extension(Path file) {
        String name = file.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }

    private String jsonEscape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                case '\b': result.append("\\b"); break;
                case '\f': result.append("\\f"); break;
                default:
                    if (current < 0x20) {
                        result.append(String.format("\\u%04x", (int) current));
                    } else {
                        result.append(current);
                    }
            }
        }
        return result.toString();
    }
    private String truncate(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 500)); }

    /**
     * 把异常整理成可落库、可展示的失败原因。
     * <p>
     * 两件事：① 截断，Git 输出可能很长；② 抹掉 URL 里的凭据——
     * Git 报错经常回显完整地址，如果用户把 token 写进了 gitUrl（如 https://user:token@host/repo），
     * 原样落库等于把凭据存进了普通表。
     */
    private String sanitizeGitError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        String redacted = message.replaceAll("(://)[^/@\\s]+@", "$1***@");
        return truncate(redacted);
    }

    private void deleteWorkspace(Path workspace) {
        if (workspace == null) return;
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE; }
            });
        } catch (Exception e) { log.warn("delete index workspace failed: {}", e.getMessage()); }
    }

    private static class IndexCounter { private int count; }

    /** 单文件源码片段总额度：用完之后该文件剩余的符号切片不再落源码，避免超大文件把库撑爆 */
    private static class SnippetBudget {
        private int remaining;

        SnippetBudget(int total) {
            this.remaining = total;
        }

        boolean tryConsume(int amount) {
            if (remaining <= 0) {
                return false;
            }
            remaining -= amount;
            return true;
        }
    }
}
