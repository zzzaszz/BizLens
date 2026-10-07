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
import java.util.Map;
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
        Path isolatedHome = null;
        AiCodeProjectEntity project = null;
        try {
            project = projectMapper.getById(projectId);
            if (project == null) return;
            projectMapper.updateStatus(projectId, "INDEXING");
            validateGitUrl(project.getGitUrl());
            workspace = Files.createTempDirectory("bizlens-index-");
            // 每次索引都给 git 一个"干净的家"：里面没有任何凭据与配置，见 applyIsolatedGitEnv
            isolatedHome = Files.createTempDirectory("bizlens-git-home-");
            runGitClone(project, workspace, isolatedHome);
            String commit = runGitCommand(workspace, isolatedHome, "rev-parse", "HEAD").trim();
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
            projectMapper.markFailed(projectId, buildFailureReason(project, e));
        } finally {
            deleteWorkspace(workspace);
            deleteWorkspace(isolatedHome);
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

    private void runGitClone(AiCodeProjectEntity project, Path workspace, Path isolatedHome) throws Exception {
        runCommand(workspace.getParent(), isolatedHome, "git", "clone", "--depth", "1", "--branch",
                project.getGitBranch(), project.getGitUrl(), workspace.toString());
    }

    private String runGitCommand(Path directory, Path isolatedHome, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        return runCommand(directory, isolatedHome, command);
    }

    /**
     * 给 git 进程一套"不带任何环境凭据"的运行环境。
     * <p>
     * git 默认会自己去找运行机器上的凭据：全局/系统配置里的 credential.helper、
     * {@code ~/.git-credentials}、{@code ~/.ssh}、SSH agent、系统凭据管理器。
     * 这些凭据属于"跑这个服务的人"，与 BizLens 的用户授权毫无关系。
     * 2026-10-07 实测：一个**私有**仓库在项目未配任何凭据的情况下照样被克隆成功，
     * 用的就是本机 {@code ~/.git-credentials} 里那条 github.com 凭据——
     * 等于任何注册用户都能借机器身份读私有代码，与"不向客户泄露源码"的产品定位直接冲突。
     * <p>
     * 这里把 HOME / 全局配置 / 系统配置统统指向空处并关掉交互提示，
     * 让 git 只能看到应用显式提供的东西。当前还没有按项目的凭据注入能力，
     * 所以**私有仓库会直接克隆失败**——这是刻意的：宁可失败，也不要静默借用机器身份。
     * 后续接入按项目凭据时，在这里把凭据经 {@code GIT_ASKPASS} 注入即可。
     */
    private void applyIsolatedGitEnv(ProcessBuilder builder, Path isolatedHome) {
        Map<String, String> env = builder.environment();

        // HOME 相关的三个变量都改掉：git 找 ~/.gitconfig 和 ~/.git-credentials 靠它们
        String home = isolatedHome.toAbsolutePath().toString();
        env.put("HOME", home);
        env.put("USERPROFILE", home);
        env.put("XDG_CONFIG_HOME", home);

        // 全局配置指向一个不存在的文件（空目录里没有），系统配置直接禁用
        env.put("GIT_CONFIG_GLOBAL", isolatedHome.resolve(".gitconfig").toAbsolutePath().toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        // 再用 GIT_CONFIG_COUNT 把 credential.helper 显式清空：空值表示重置 helper 列表，
        // 这样即使将来又冒出来一个系统级 helper 也套不上
        env.put("GIT_CONFIG_COUNT", "1");
        env.put("GIT_CONFIG_KEY_0", "credential.helper");
        env.put("GIT_CONFIG_VALUE_0", "");

        // 不允许交互，也不给 askpass 兜底程序：要么应用自己给了凭据，要么就失败
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.remove("GIT_ASKPASS");
        env.remove("SSH_ASKPASS");
        // SSH agent 同样是"机器身份"，一并摘掉
        env.remove("SSH_AUTH_SOCK");
        env.remove("GIT_SSH_COMMAND");
    }

    private String runCommand(Path directory, Path isolatedHome, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        applyIsolatedGitEnv(builder, isolatedHome);
        Process process = builder.start();
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
    /**
     * 组装落库的失败原因。
     * <p>
     * 鉴权类失败要给出可操作的说明：克隆已经不再借用运行机器的凭据，
     * 而按项目的凭据注入还没做，所以此刻私有仓库必然是失败的——
     * 用户如果不知道这一点，只会反复去改「Git 凭据」字段（那个字段并不参与克隆）。
     */
    private String buildFailureReason(AiCodeProjectEntity project, Exception e) {
        String reason = sanitizeGitError(e);
        String lower = reason.toLowerCase(Locale.ROOT);
        boolean authFailure = lower.contains("terminal prompts disabled")
                || lower.contains("could not read username")
                || lower.contains("could not read password")
                || lower.contains("authentication failed")
                || lower.contains("permission denied")
                || lower.contains("publickey")
                || lower.contains("403");
        if (authFailure) {
            return "无法访问该仓库：克隆已停用运行机器的 Git 凭据（不再借用服务账号身份），"
                    + "按项目的凭据注入尚未实现，因此当前只能索引可匿名访问的仓库；原始错误：" + reason;
        }
        if (project != null && project.getCredentialRef() != null && !project.getCredentialRef().trim().isEmpty()) {
            return "Git 凭据字段不参与克隆，填了也不生效；原始错误：" + reason;
        }
        return reason;
    }

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
