package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import zzz.service.forum.ai.AiEmbeddingProvider;
import zzz.service.forum.ai.CodeSecretScanner;
import zzz.service.forum.ai.EmbeddingVectorUtils;
import zzz.service.forum.domain.AiKnowledgeChunkEntity;
import zzz.service.forum.domain.AiKnowledgeDocumentEntity;
import zzz.service.forum.domain.AiKnowledgeSourceEntity;
import zzz.service.forum.mapper.AiKnowledgeChunkMapper;
import zzz.service.forum.mapper.AiKnowledgeDocumentMapper;
import zzz.service.forum.mapper.AiKnowledgeSourceMapper;
import zzz.service.forum.service.AiKnowledgeIndexService;
import zzz.service.forum.utils.CryptoService;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service("aiKnowledgeIndexService")
public class AiKnowledgeIndexServiceImpl implements AiKnowledgeIndexService {
    private static final Logger log = LoggerFactory.getLogger(AiKnowledgeIndexServiceImpl.class);
    private static final String INDEX_WORK_PREFIX = "bizlens-index-";
    private static final String GIT_HOME_PREFIX = "bizlens-git-home-";
    private static final int HEARTBEAT_SECONDS = 30;
    private static final int HEARTBEAT_GRACE_SECONDS = 180;
    private static final int DEFAULT_MAX_FILES = 2000;
    private static final long DEFAULT_MAX_FILE_BYTES = 4 * 1024 * 1024L;
    private static final int DEFAULT_MAX_CHUNK_CHARS = 1500;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private static final Set<Path> ACTIVE_WORKSPACES = ConcurrentHashMap.newKeySet();

    @Resource
    private AiKnowledgeSourceMapper sourceMapper;
    @Resource
    private AiKnowledgeDocumentMapper documentMapper;
    @Resource
    private AiKnowledgeChunkMapper chunkMapper;
    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Resource
    private CryptoService cryptoService;
    @Resource
    private AiEmbeddingProvider embeddingProvider;
    @Value("${ai.knowledge.index.max-files:2000}")
    private int configuredMaxFiles;
    @Value("${ai.knowledge.index.max-file-bytes:4194304}")
    private long configuredMaxFileBytes;
    @Value("${ai.knowledge.index.max-chunk-chars:1500}")
    private int configuredMaxChunkChars;

    private final CodeSecretScanner secretScanner = new CodeSecretScanner();
    private volatile boolean vectorPersistenceDisabled;

    @PostConstruct
    public void resetStuckIndexing() {
        try {
            int reset = sourceMapper.resetStuckIndexing("知识库索引被中断：服务在索引途中退出，请重新排队", HEARTBEAT_GRACE_SECONDS);
            if (reset > 0) log.warn("reset {} interrupted knowledge indexes", reset);
        } catch (Exception e) {
            log.warn("reset interrupted knowledge indexes failed: {}", e.getMessage());
        }
    }

    @Override
    @Async
    public void indexAsync(Long sourceId) {
        Path workspace = null;
        Path isolatedHome = null;
        Timer heartbeat = null;
        AiKnowledgeSourceEntity source = null;
        long startedAt = System.currentTimeMillis();
        try {
            source = sourceMapper.getById(sourceId);
            if (source == null) return;
            validateGitUrl(source.getGitUrl());
            sourceMapper.touchIndexHeartbeat(sourceId);
            workspace = Files.createTempDirectory(INDEX_WORK_PREFIX);
            isolatedHome = Files.createTempDirectory(GIT_HOME_PREFIX);
            ACTIVE_WORKSPACES.add(workspace);
            ACTIVE_WORKSPACES.add(isolatedHome);
            heartbeat = startHeartbeat(sourceId);
            runGitClone(source, workspace, isolatedHome);
            String commit = runGitCommand(workspace, isolatedHome, "rev-parse", "HEAD").trim();
            if (commit.equals(source.getGitCommit()) && documentMapper.countBySource(sourceId) > 0) {
                sourceMapper.markIndexed(sourceId, "READY", commit, System.currentTimeMillis() - startedAt);
                log.info("knowledge index skipped, sourceId={}, reason=same-commit", sourceId);
                return;
            }

            Path contentRoot = resolveRoot(workspace, source.getRootPath());
            List<Path> files = listMarkdownFiles(contentRoot);
            int maxFiles = maxFiles();
            long maxBytes = maxFileBytes();
            sourceMapper.startIndex(sourceId, files.size());
            IndexCounter counter = new IndexCounter();
            Set<String> currentPaths = new HashSet<>();
            for (Path file : files) {
                String relativePath = workspace.relativize(file).toString().replace('\\', '/');
                currentPaths.add(relativePath);
                counter.processed++;
                updateProgress(sourceId, counter, files.size(), relativePath);
                try {
                    if (counter.indexed >= maxFiles || Files.size(file) > maxBytes) {
                        counter.skipped++;
                    } else {
                        indexFile(sourceId, workspace, file);
                        counter.indexed++;
                    }
                } catch (Exception e) {
                    counter.skipped++;
                    log.warn("skip knowledge file {}, sourceId={}, err={}", relativePath, sourceId, e.getMessage());
                }
            }
            deleteRemovedDocuments(sourceId, currentPaths);
            sourceMapper.markIndexed(sourceId, "READY", commit, System.currentTimeMillis() - startedAt);
            log.info("knowledge source indexed, sourceId={}, files={}, skipped={}, total={}, commit={}",
                    sourceId, counter.indexed, counter.skipped, files.size(), commit);
        } catch (Exception e) {
            log.warn("knowledge source index failed, sourceId={}", sourceId, e);
            sourceMapper.markFailed(sourceId, sanitizeError(e));
        } finally {
            if (heartbeat != null) heartbeat.cancel();
            deleteWorkspace(workspace);
            deleteWorkspace(isolatedHome);
            if (workspace != null) ACTIVE_WORKSPACES.remove(workspace);
            if (isolatedHome != null) ACTIVE_WORKSPACES.remove(isolatedHome);
        }
    }

    private void indexFile(Long sourceId, Path workspace, Path file) throws Exception {
        String original = normalizeLineBreaks(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        CodeSecretScanner.ScanResult scan = secretScanner.scan(original);
        String text = scan.getSanitizedText();
        String relativePath = workspace.relativize(file).toString().replace('\\', '/');
        String checksum = sha256(original);
        AiKnowledgeDocumentEntity old = documentMapper.getBySourceAndPath(sourceId, relativePath);
        if (old != null && checksum.equals(old.getChecksum())) return;

        Date now = new Date();
        AiKnowledgeDocumentEntity document = old == null ? new AiKnowledgeDocumentEntity() : old;
        if (old == null) {
            document.setId(snowflakeIdGenerator.nextId());
            document.setSourceId(sourceId);
            document.setPath(relativePath);
            document.setCreatedAt(now);
        }
        document.setTitle(titleOf(file, text));
        document.setChecksum(checksum);
        document.setSizeBytes((int) Math.min(Integer.MAX_VALUE, Files.size(file)));
        document.setChunkCount(0);
        document.setUpdatedAt(now);
        if (old == null) documentMapper.insert(document);
        else documentMapper.update(document);
        chunkMapper.deleteByDocument(document.getId());

        List<MarkdownChunk> chunks = splitMarkdown(text, maxChunkChars());
        int index = 0;
        for (MarkdownChunk part : chunks) {
            AiKnowledgeChunkEntity chunk = new AiKnowledgeChunkEntity();
            chunk.setId(snowflakeIdGenerator.nextId());
            chunk.setDocumentId(document.getId());
            chunk.setChunkIndex(index++);
            chunk.setHeadingPath(part.headingPath);
            chunk.setHeadingLevel(part.headingLevel);
            chunk.setContent(part.content);
            chunk.setEmbeddingVector(embeddingLiteral(part.headingPath + "\n" + part.content));
            chunk.setMetadata("{\"path\":\"" + jsonEscape(relativePath) + "\",\"startLine\":"
                    + part.startLine + ",\"endLine\":" + part.endLine + "}");
            chunk.setCreatedAt(now);
            insertChunk(chunk);
        }
        documentMapper.updateChunkCount(document.getId(), chunks.size());
        if (scan.isRedacted()) log.warn("knowledge secrets redacted, sourceId={}, path={}, rules={}", sourceId, relativePath, scan.getRules());
    }

    private List<MarkdownChunk> splitMarkdown(String text, int maxChars) {
        String[] lines = text.split("\\n", -1);
        List<MarkdownChunk> result = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int bufferStart = 1;
        int bufferLevel = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                flushChunk(result, buffer, headings, bufferLevel, bufferStart, i, maxChars);
                int level = matcher.group(1).length();
                while (headings.size() >= level) headings.remove(headings.size() - 1);
                headings.add(matcher.group(2).trim());
                bufferLevel = level;
                bufferStart = i + 1;
                continue;
            }
            if (buffer.length() == 0) bufferStart = i + 1;
            buffer.append(line).append('\n');
            if (buffer.length() >= maxChars) {
                flushChunk(result, buffer, headings, bufferLevel, bufferStart, i + 1, maxChars);
                bufferStart = i + 2;
            }
        }
        flushChunk(result, buffer, headings, bufferLevel, bufferStart, lines.length, maxChars);
        return result;
    }

    private void flushChunk(List<MarkdownChunk> result, StringBuilder buffer, List<String> headings,
                            int level, int startLine, int endLine, int maxChars) {
        String value = buffer.toString().trim();
        buffer.setLength(0);
        if (value.isEmpty()) return;
        if (value.length() > maxChars) value = value.substring(0, maxChars);
        String headingPath = join(headings);
        result.add(new MarkdownChunk(value, headingPath, level, startLine, Math.max(startLine, endLine)));
    }

    private void deleteRemovedDocuments(Long sourceId, Set<String> currentPaths) {
        List<String> oldPaths = documentMapper.listPathsBySource(sourceId);
        if (oldPaths == null) return;
        for (String oldPath : oldPaths) {
            if (!currentPaths.contains(oldPath)) documentMapper.deleteBySourceAndPath(sourceId, oldPath);
        }
    }

    private List<Path> listMarkdownFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) throw new IOException("知识库子目录不存在: " + root.getFileName());
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if ((name.endsWith(".md") || name.endsWith(".markdown")) && !isIgnored(file)) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private boolean isIgnored(Path file) {
        for (Path part : file) {
            String name = part.toString();
            if (".git".equals(name) || ".obsidian".equals(name) || "node_modules".equals(name)
                    || "attachments".equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private Path resolveRoot(Path workspace, String rootPath) throws IOException {
        String normalized = rootPath == null ? "" : rootPath.trim().replace('\\', '/');
        if (normalized.contains("..")) throw new IOException("知识库子目录不合法");
        Path root = workspace.resolve(normalized).normalize();
        if (!root.startsWith(workspace.toAbsolutePath().normalize())) throw new IOException("知识库子目录越界");
        return root;
    }

    private void updateProgress(Long sourceId, IndexCounter counter, int total, String currentFile) {
        try { sourceMapper.updateIndexProgress(sourceId, counter.processed, counter.skipped, currentFile); }
        catch (Exception e) { log.debug("knowledge index progress failed: {}", e.getMessage()); }
    }

    private Timer startHeartbeat(Long sourceId) {
        Timer timer = new Timer("bizlens-knowledge-heartbeat-" + sourceId, true);
        timer.schedule(new TimerTask() {
            @Override public void run() {
                try { sourceMapper.touchIndexHeartbeat(sourceId); }
                catch (Exception e) { log.debug("knowledge index heartbeat failed: {}", e.getMessage()); }
            }
        }, TimeUnit.SECONDS.toMillis(HEARTBEAT_SECONDS), TimeUnit.SECONDS.toMillis(HEARTBEAT_SECONDS));
        return timer;
    }

    private void runGitClone(AiKnowledgeSourceEntity source, Path workspace, Path isolatedHome) throws Exception {
        String token = cryptoService.decrypt(source.getCredentialEnc());
        GitCredential credential = token == null || token.trim().isEmpty() ? null
                : new GitCredential(resolveCredentialUser(source.getCredentialUser(), source.getGitUrl()), token.trim());
        runCommand(workspace.getParent(), isolatedHome, credential, "git", "clone", "--depth", "1", "--branch",
                source.getGitBranch(), source.getGitUrl(), workspace.toString());
    }

    private String runGitCommand(Path directory, Path isolatedHome, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        return runCommand(directory, isolatedHome, null, command);
    }

    private String runCommand(Path directory, Path isolatedHome, GitCredential credential, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        String home = isolatedHome.toAbsolutePath().toString();
        env.put("HOME", home); env.put("USERPROFILE", home); env.put("XDG_CONFIG_HOME", home);
        env.put("GIT_CONFIG_GLOBAL", isolatedHome.resolve(".gitconfig").toAbsolutePath().toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.remove("GIT_ASKPASS"); env.remove("SSH_ASKPASS"); env.remove("SSH_AUTH_SOCK"); env.remove("GIT_SSH_COMMAND");
        for (String key : new String[]{"HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy"}) env.remove(key);
        String proxy = trimToNull(System.getenv("BIZLENS_GIT_PROXY"));
        if (proxy != null) { env.put("HTTP_PROXY", proxy); env.put("HTTPS_PROXY", proxy); env.put("http_proxy", proxy); env.put("https_proxy", proxy); }
        String noProxy = trimToNull(System.getenv("BIZLENS_GIT_NO_PROXY"));
        if (noProxy != null) { env.put("NO_PROXY", noProxy); env.put("no_proxy", noProxy); }
        if (credential == null) {
            env.put("GIT_CONFIG_COUNT", "1"); env.put("GIT_CONFIG_KEY_0", "credential.helper"); env.put("GIT_CONFIG_VALUE_0", "");
        } else {
            env.put("GIT_CONFIG_COUNT", "2");
            env.put("GIT_CONFIG_KEY_0", "credential.helper"); env.put("GIT_CONFIG_VALUE_0", "");
            env.put("GIT_CONFIG_KEY_1", "credential.helper");
            env.put("GIT_CONFIG_VALUE_1", "!f() { echo username=" + credential.username + "; echo password=\"$BIZLENS_GIT_TOKEN\"; }; f");
            env.put("BIZLENS_GIT_TOKEN", credential.token);
        }
        Process process = builder.start();
        boolean done = process.waitFor(120, TimeUnit.SECONDS);
        if (!done) { process.destroyForcibly(); throw new IOException("Git 命令执行超时"); }
        String output;
        try (InputStream input = process.getInputStream()) { output = new String(readAll(input), StandardCharsets.UTF_8); }
        if (process.exitValue() != 0) throw new IOException("Git 命令执行失败: " + truncate(output));
        return output;
    }

    private byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int read;
        while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private void validateGitUrl(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://")))
            throw new IllegalArgumentException("个人知识库当前仅支持 HTTP(S) Git 地址");
    }

    private String resolveCredentialUser(String configured, String url) {
        if (configured != null && !configured.trim().isEmpty()) return configured.trim();
        return url != null && url.toLowerCase(Locale.ROOT).contains("github.") ? "x-access-token" : "oauth2";
    }

    private String embeddingLiteral(String text) {
        if (vectorPersistenceDisabled || embeddingProvider == null || !embeddingProvider.isEnabled()) return null;
        try { return EmbeddingVectorUtils.toPgVectorLiteral(embeddingProvider.embed(text), embeddingProvider.dimension()); }
        catch (Exception e) { log.warn("knowledge embedding failed, skip vector persistence: {}", e.getMessage()); return null; }
    }

    private void insertChunk(AiKnowledgeChunkEntity chunk) throws Exception {
        try { chunkMapper.insert(chunk); }
        catch (Exception e) {
            if (chunk.getEmbeddingVector() != null && isVectorInfrastructureError(e)) {
                vectorPersistenceDisabled = true; chunk.setEmbeddingVector(null);
                log.warn("knowledge vector persistence disabled, retry without embedding: {}", e.getMessage());
                chunkMapper.insert(chunk); return;
            }
            throw e;
        }
    }

    private boolean isVectorInfrastructureError(Exception e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("embedding_vec") || message.contains("type \"vector\"")
                || message.contains("type vector") || message.contains("operator does not exist")
                || message.contains("does not exist") && message.contains("vector");
    }

    private int maxFiles() { return configuredMaxFiles > 0 ? configuredMaxFiles : DEFAULT_MAX_FILES; }
    private long maxFileBytes() { return configuredMaxFileBytes > 0 ? configuredMaxFileBytes : DEFAULT_MAX_FILE_BYTES; }
    private int maxChunkChars() { return configuredMaxChunkChars > 100 ? configuredMaxChunkChars : DEFAULT_MAX_CHUNK_CHARS; }

    private String titleOf(Path file, String text) {
        for (String line : text.split("\\n")) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches() && matcher.group(1).length() == 1) return matcher.group(2).trim();
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String join(List<String> values) { return values.isEmpty() ? "" : String.join(" / ", values); }
    private String normalizeLineBreaks(String value) { return value.replace("\r\n", "\n").replace('\r', '\n'); }
    private String trimToNull(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private String sanitizeError(Exception e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage();
        return truncate(message.replaceAll("(://)[^/@\\s]+@", "$1***@"));
    }

    private String truncate(String value) { return value == null ? "" : value.substring(0, Math.min(value.length(), 500)); }

    private boolean deleteWorkspace(Path workspace) {
        if (workspace == null) return false;
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE; }
            });
            return true;
        } catch (Exception e) { log.warn("delete knowledge workspace failed: {}", e.getMessage()); return false; }
    }

    private static final class GitCredential {
        private final String username;
        private final String token;
        private GitCredential(String username, String token) { this.username = username; this.token = token; }
    }

    private static final class IndexCounter { private int processed; private int indexed; private int skipped; }

    private static final class MarkdownChunk {
        private final String content;
        private final String headingPath;
        private final int headingLevel;
        private final int startLine;
        private final int endLine;
        private MarkdownChunk(String content, String headingPath, int headingLevel, int startLine, int endLine) {
            this.content = content; this.headingPath = headingPath; this.headingLevel = headingLevel;
            this.startLine = startLine; this.endLine = endLine;
        }
    }
}
