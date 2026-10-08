package zzz.service.forum.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import zzz.service.forum.domain.AiCodeChunkEntity;
import zzz.service.forum.domain.AiCodeDocumentEntity;
import zzz.service.forum.domain.AiCodeProjectEntity;
import zzz.service.forum.domain.dto.AiCodeRelationCandidate;
import zzz.service.forum.ai.AiEmbeddingProvider;
import zzz.service.forum.ai.CodeSymbolExtractor;
import zzz.service.forum.ai.CodeSecretScanner;
import zzz.service.forum.ai.EmbeddingVectorUtils;
import zzz.service.forum.mapper.AiCodeChunkMapper;
import zzz.service.forum.mapper.AiCodeDocumentMapper;
import zzz.service.forum.mapper.AiCodeProjectMapper;
import zzz.service.forum.service.AiCodeIndexService;
import zzz.service.forum.utils.CryptoService;
import zzz.service.forum.utils.SnowflakeIdGenerator;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.File;
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
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;

/**
 * 基础代码索引器：临时浅克隆、保存元数据与切片源码、删除工作区。
 * <p>
 * 每个切片会落一份截断后的源码原文（source_snippet），供对话时按关键词召回、
 * 注入模型理解业务逻辑；回答侧由 AiSafetyGuard 拦截源码输出。
 * 代码结构提取已覆盖 Java AST、Mapper XML 和 Vue SFC；仍不执行 Embedding，不做向量化。
 */
@Service("aiCodeIndexService")
public class AiCodeIndexServiceImpl implements AiCodeIndexService {

    private static final Logger log = LoggerFactory.getLogger(AiCodeIndexServiceImpl.class);
    private static final int DEFAULT_MAX_FILES = 2000;
    private static final long DEFAULT_MAX_FILE_BYTES = 2 * 1024 * 1024L;
    private static final int MAX_SYMBOLS_PER_FILE = 2000;
    /** 索引工作区目录前缀（克隆下来的代码放这里，用完删） */
    private static final String INDEX_WORK_PREFIX = "bizlens-index-";
    /** git 隔离 HOME 目录前缀（里面只有配置，没有凭据） */
    private static final String GIT_HOME_PREFIX = "bizlens-git-home-";
    /**
     * 临时工作区超过这个时长仍未清理，就视为上一轮进程留下的垃圾。
     * 取 1 小时：正常浅克隆远用不到这么久，而真有长任务在跑时还有内存里的在用标记兜底。
     */
    private static final long STALE_WORKSPACE_HOURS = 1;
    /** 索引心跳间隔（秒）：运行期间定期推进 updated_at */
    private static final int HEARTBEAT_SECONDS = 30;
    /** 单条 Git 命令最长运行时间，避免网络异常让异步任务无期限占用线程。 */
    private static final int GIT_COMMAND_TIMEOUT_SECONDS = 120;
    /** 克隆失败时最多重试一次，仅对明确的瞬时网络错误重试。 */
    private static final int GIT_CLONE_ATTEMPTS = 2;
    /**
     * 心跳宽限（秒）：超过这么久没有心跳就认为索引进程已经没了。
     * 取心跳间隔的 6 倍，留足网络/GC 抖动余量；前端用它判断"索引中"还是"已中断"。
     */
    private static final int HEARTBEAT_GRACE_SECONDS = 180;
    /** 本进程正在使用的临时目录；清理时跳过它们，避免误删正在跑的索引 */
    private static final Set<Path> ACTIVE_WORKSPACES = ConcurrentHashMap.newKeySet();
    /** 用户主动终止索引时写入；索引线程在文件边界和收尾阶段主动检查。 */
    private static final Set<Long> CANCEL_REQUESTS = ConcurrentHashMap.newKeySet();
    /** 单个切片的源码片段上限，防止一个巨型方法把库撑爆 */
    private static final int MAX_SNIPPET_CHARS = 1200;
    /** 每个文件所有切片的源码总量上限，兜住 MAX_SYMBOLS_PER_FILE 带来的最坏情况 */
    private static final int MAX_SNIPPET_CHARS_PER_FILE = 20000;
    /**
     * FILE 级切片只留文件头，它主要用来提供"这个文件大概是什么"，不需要全量。
     * <p>
     * 从 800 提到 2400：800 字符连 imports 都放不完，控制器这类文件往往刚好切在类声明之后、
     * 第一个方法之前，看起来像"这个类什么方法都没有"。实测问「这个类的接口有哪些」时，
     * 模型只能回答"片段在方法注解处就被截断了"。
     * 代价是每个文件多约 1.6KB（2000 文件的项目约多 3MB），比答不出问题划算。
     */
    private static final int MAX_FILE_HEAD_CHARS = 2400;
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
    @Resource
    private CryptoService cryptoService;
    @Resource
    private AiEmbeddingProvider embeddingProvider;
    @Value("${ai.index.max-files:2000}")
    private int configuredMaxFiles;
    @Value("${ai.index.max-file-bytes:2097152}")
    private long configuredMaxFileBytes;
    @Value("${ai.index.relation-max-pairs:5000000}")
    private long configuredRelationMaxPairs;
    private final CodeSymbolExtractor symbolExtractor = new CodeSymbolExtractor();
    private final CodeSecretScanner secretScanner = new CodeSecretScanner();
    private volatile boolean vectorPersistenceDisabled;

    /** 启动时先做两件收尾：清遗留工作区、把上一轮中断的索引状态收敛掉 */
    @PostConstruct
    public void cleanUpOnStartup() {
        cleanStaleWorkspaces();
        resetStuckIndexing();
    }

    /**
     * 把上一轮进程中断的索引收敛成失败态。
     * <p>
     * 进程在索引途中退出（重启、被杀）时 {@code finally} 不执行，状态会永远停在
     * QUEUED/INDEXING——页面上表现为"一直索引中"，而实际上什么都没在跑。
     * 实测就是这么卡住的。这里只重置超过 {@link #HEARTBEAT_GRACE_SECONDS} 秒没有心跳的，
     * 避免误伤其它实例正在跑的任务。
     */
    void resetStuckIndexing() {
        try {
            int reset = projectMapper.resetStuckIndexing(
                    "索引任务被中断：服务在索引途中退出（重启或被终止），本次索引已中止，请重新排队索引",
                    HEARTBEAT_GRACE_SECONDS);
            if (reset > 0) {
                log.warn("已把 {} 个中断的索引任务置为失败（服务曾在其运行途中退出）", reset);
            }
        } catch (Exception e) {
            log.warn("收敛中断的索引状态失败: {}", e.getMessage());
        }
    }

    @Override
    @Async
    public void indexAsync(Long projectId) {
        Path workspace = null;
        Path isolatedHome = null;
        AiCodeProjectEntity project = null;
        Timer heartbeat = null;
        long startedAt = System.currentTimeMillis();
        try {
            // 顺手清一次遗留目录：长期运行的服务不该越跑越占空间
            cleanStaleWorkspaces();
            project = projectMapper.getById(projectId);
            if (project == null) return;
            throwIfCancelRequested(projectId);
            // 心跳：让"这个 INDEXING 是真在跑还是进程已经没了"可判断（见 HEARTBEAT_* 常量）
            projectMapper.touchIndexHeartbeat(projectId);
            heartbeat = startHeartbeat(projectId);
            validateGitUrl(project.getGitUrl());
            workspace = Files.createTempDirectory(INDEX_WORK_PREFIX);
            ACTIVE_WORKSPACES.add(workspace);
            // 每次索引都给 git 一个"干净的家"：里面没有任何凭据与配置，见 applyIsolatedGitEnv
            isolatedHome = Files.createTempDirectory(GIT_HOME_PREFIX);
            ACTIVE_WORKSPACES.add(isolatedHome);
            runGitClone(project, workspace, isolatedHome);
            String commit = runGitCommand(workspace, isolatedHome, "rev-parse", "HEAD").trim();
            if (commit.equals(project.getGitCommit()) && documentMapper.countByProject(projectId) > 0) {
                long durationMs = System.currentTimeMillis() - startedAt;
                projectMapper.markIndexed(projectId, "READY", commit, durationMs);
                log.info("code project index skipped, projectId={}, reason=same-commit, commit={}, durationMs={}",
                        projectId, commit, durationMs);
                return;
            }
            documentMapper.deleteByProject(projectId);
            int totalFiles = countIndexableFiles(workspace);
            projectMapper.startIndex(projectId, totalFiles);
            IndexCounter counter = new IndexCounter();
            final Path root = workspace;
            final int maxFiles = maxFiles();
            final long maxFileBytes = maxFileBytes();
            log.info("code project index limits, projectId={}, maxFiles={}, maxFileBytes={}",
                    projectId, maxFiles, maxFileBytes);
            Files.walkFileTree(workspace, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    throwIfCancelRequested(projectId);
                    if (!isIndexable(file) || isIgnored(root, file)) return FileVisitResult.CONTINUE;
                    counter.visited++;
                    String currentFile = root.relativize(file).toString().replace('\\', '/');
                    updateProgress(projectId, counter, totalFiles, currentFile);
                    if (counter.visited > maxFiles || attrs.size() > maxFileBytes) {
                        counter.skipped++;
                        counter.processed++;
                        updateProgress(projectId, counter, totalFiles, currentFile);
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        throwIfCancelRequested(projectId);
                        indexFile(projectId, root, file, attrs.size());
                        throwIfCancelRequested(projectId);
                        counter.indexed++;
                    } catch (IndexCancelledException e) {
                        throw e;
                    } catch (Exception e) {
                        counter.skipped++;
                        log.warn("skip code file {}: {}", file, e.getMessage());
                    }
                    counter.processed++;
                    updateProgress(projectId, counter, totalFiles, currentFile);
                    return FileVisitResult.CONTINUE;
                }
            });
            throwIfCancelRequested(projectId);
            updateProgress(projectId, counter, totalFiles, "生成调用关系");
            throwIfCancelRequested(projectId);
            rebuildRelations(projectId);
            throwIfCancelRequested(projectId);
            long durationMs = System.currentTimeMillis() - startedAt;
            projectMapper.markIndexed(projectId, "READY", commit, durationMs);
            log.info("code project indexed, projectId={}, files={}, skipped={}, total={}, commit={}, durationMs={}",
                    projectId, counter.indexed, counter.skipped, totalFiles, commit, durationMs);
        } catch (IndexCancelledException e) {
            log.info("code project index cancelled, projectId={}", projectId);
            projectMapper.markFailed(projectId, e.getMessage());
        } catch (Exception e) {
            log.warn("code project index failed, projectId={}", projectId, e);
            // 同时写库：前端只看到一个「失败」标签是不够的，要能看到为什么失败
            projectMapper.markFailed(projectId, buildFailureReason(project, e));
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel();
            }
            deleteWorkspace(workspace);
            deleteWorkspace(isolatedHome);
            if (workspace != null) {
                ACTIVE_WORKSPACES.remove(workspace);
            }
            if (isolatedHome != null) {
                ACTIVE_WORKSPACES.remove(isolatedHome);
            }
            CANCEL_REQUESTS.remove(projectId);
        }
    }

    @Override
    public void requestCancel(Long projectId) {
        if (projectId != null) {
            CANCEL_REQUESTS.add(projectId);
        }
    }

    private int countIndexableFiles(Path root) throws IOException {
        final int[] total = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isIndexable(file) && !isIgnored(root, file)) total[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    private int maxFiles() {
        return configuredMaxFiles > 0 ? configuredMaxFiles : DEFAULT_MAX_FILES;
    }

    private long maxFileBytes() {
        return configuredMaxFileBytes > 0 ? configuredMaxFileBytes : DEFAULT_MAX_FILE_BYTES;
    }

    private void updateProgress(Long projectId, IndexCounter counter, int totalFiles, String currentFile) {
        try {
            // 每个文件都写当前路径，页面才能知道大仓库索引卡在哪个文件；心跳本身仍由定时任务负责。
            projectMapper.updateIndexProgress(projectId, counter.processed, counter.skipped, currentFile);
        } catch (Exception e) {
            log.debug("索引进度写入失败: {}", e.getMessage());
        }
    }

    /**
     * 起一个后台心跳，每 {@link #HEARTBEAT_SECONDS} 秒把项目的 updated_at 推一下。
     * <p>
     * 没有它就只能靠一个很宽的时间窗猜任务死没死；有了心跳，"超过
     * {@link #HEARTBEAT_GRACE_SECONDS} 秒没动静"就等价于"进程没了"，
     * 前端可以立刻显示「索引中断」而不是假装还在跑。守护线程，随任务结束而 cancel。
     */
    private Timer startHeartbeat(Long projectId) {
        Timer timer = new Timer("bizlens-index-heartbeat-" + projectId, true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    projectMapper.touchIndexHeartbeat(projectId);
                } catch (Exception e) {
                    // 心跳失败不影响索引本身，下一次再试
                    log.debug("索引心跳写入失败: {}", e.getMessage());
                }
            }
        }, TimeUnit.SECONDS.toMillis(HEARTBEAT_SECONDS), TimeUnit.SECONDS.toMillis(HEARTBEAT_SECONDS));
        return timer;
    }

    /**
     * 清理上一轮进程遗留的临时工作区。
     * <p>
     * 工作区正常会在 {@code finally} 里删掉，但两种情况下会漏：
     * ① 进程在索引途中被杀，finally 根本不执行；
     * ② Windows 上 git 进程还持有文件句柄，删除失败被吞掉。
     * 留下的是一整份浅克隆（几十到几百 MB），而且没有任何机制会回头清理——
     * 实测本机就攒了 25 个、309 MB。服务器上跑几个月能堆到几十 GB。
     * <p>
     * 所以启动时和每次索引前扫一遍临时目录：跳过本进程正在用的，
     * 以及最后修改时间在 {@link #STALE_WORKSPACE_HOURS} 小时以内的，其余删掉。
     */
    void cleanStaleWorkspaces() {
        Path tempRoot = Paths.get(System.getProperty("java.io.tmpdir", "."));
        if (!Files.isDirectory(tempRoot)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(STALE_WORKSPACE_HOURS);
        int removed = 0;
        long freedBytes = 0;
        try (Stream<Path> entries = Files.list(tempRoot)) {
            for (Iterator<Path> it = entries.iterator(); it.hasNext(); ) {
                Path entry = it.next();
                String name = entry.getFileName().toString();
                if (!name.startsWith(INDEX_WORK_PREFIX) && !name.startsWith(GIT_HOME_PREFIX)) {
                    continue;
                }
                if (ACTIVE_WORKSPACES.contains(entry)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(entry).toMillis() > cutoff) {
                        continue;
                    }
                    long size = directorySize(entry);
                    if (deleteWorkspace(entry)) {
                        removed++;
                        freedBytes += size;
                    }
                } catch (Exception e) {
                    log.warn("清理遗留临时工作区失败: {}, err={}", entry, e.getMessage());
                }
            }
        } catch (Exception e) {
            // 清理失败不该影响正常索引
            log.warn("扫描临时工作区失败: {}", e.getMessage());
        }
        if (removed > 0) {
            log.info("已清理遗留的索引临时工作区 {} 个，释放约 {} MB", removed, freedBytes / 1024 / 1024);
        }
    }

    /** 目录总字节数，只用于日志里报"释放了多少" */
    private long directorySize(Path dir) {
        final long[] total = {0L};
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            // 统计失败就算了，不影响删除
        }
        return total[0];
    }

    /**
     * 统一换行符为 {@code \n}。
     * <p>
     * 必须做：提取符号时用 {@code split("\\R")} 拆行（{@code \R} 把 {@code \r}、{@code }、
     * {@code  } 等都当换行），而符号的行号是用"数 {@code \n} 的个数"算出来的。
     * 文件里只要有一个非 {@code \n} 的换行符，两套口径就错开一格，记录的行号落到空行上，
     * 取出的"源码片段"只有一个换行符——这批长度为 1 的残片长度最短，
     * 在召回排序里排最前，会把检索名额全占掉（表现为"问某接口的权限，模型说片段里找不到"）。
     */
    private String normalizeLineBreaks(String text) {
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('', '\n')
                .replace(' ', '\n')
                .replace(' ', '\n')
                .replace('\f', '\n')
                .replace('', '\n');
    }

    private void indexFile(Long projectId, Path root, Path file, long bytes) throws Exception {
        String originalText = normalizeLineBreaks(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        CodeSecretScanner.ScanResult scan = secretScanner.scan(originalText);
        String text = scan.getSanitizedText();
        String relativePath = root.relativize(file).toString().replace('\\', '/');
        String language = extension(file);
        // 用原文 checksum 判断文件是否变化，落库内容使用脱敏后的文本。
        String checksum = sha256(originalText);
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
        document.setSummary("代码文件 " + relativePath + "，语言 " + language + "，大小 " + bytes + " 字节"
                + (scan.isRedacted() ? "，已脱敏敏感信息" : ""));
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
        // FILE 切片保存完整正文，文件级检索才能在需要时覆盖整个文件；普通召回仍由上下文预算控制。
        chunk.setSourceSnippet(text);
        chunk.setEmbeddingVector(embeddingLiteral(document.getSummary() + "\n" + text));
        chunk.setMetadata("{\"path\":\"" + jsonEscape(relativePath) + "\",\"language\":\"" + jsonEscape(language) + "\",\"startLine\":1,\"endLine\":" + document.getEndLine() + "}");
        chunk.setCreatedAt(now);
        insertChunk(chunk);
        if (scan.isRedacted()) {
            log.warn("code secrets redacted before persistence, projectId={}, path={}, rules={}",
                    projectId, relativePath, scan.getRules());
        }

        int symbolCount = 0;
        for (CodeSymbolExtractor.Symbol symbol : symbolExtractor.extract(language, text)) {
            if (symbolCount++ >= MAX_SYMBOLS_PER_FILE) break;
            AiCodeChunkEntity symbolChunk = new AiCodeChunkEntity();
            symbolChunk.setId(snowflakeIdGenerator.nextId());
            symbolChunk.setDocumentId(document.getId());
            symbolChunk.setChunkType(symbol.getType());
            symbolChunk.setSymbolName(symbol.getName());
            symbolChunk.setBusinessSummary(symbol.getSummary() + "，文件 " + relativePath);
            // 超出源码预算时仍保存符号名和行号，文件级完整检索不能因单个大文件而遗漏方法目录。
            String snippet = budget.tryConsume(MAX_SNIPPET_CHARS)
                    ? sliceLines(lines, symbol.getStartLine(), symbol.getEndLine(), MAX_SNIPPET_CHARS)
                    : null;
            symbolChunk.setSourceSnippet(snippet);
            symbolChunk.setEmbeddingVector(embeddingLiteral(symbolChunk.getBusinessSummary() + "\n" + (snippet == null ? "" : snippet)));
            symbolChunk.setMetadata("{\"path\":\"" + jsonEscape(relativePath) + "\",\"language\":\"" + jsonEscape(language) + "\",\"startLine\":" + symbol.getStartLine() + ",\"endLine\":" + symbol.getEndLine() + "}");
            symbolChunk.setCreatedAt(now);
            insertChunk(symbolChunk);
        }
    }

    private String embeddingLiteral(String text) {
        if (vectorPersistenceDisabled || embeddingProvider == null || !embeddingProvider.isEnabled()
                || text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return EmbeddingVectorUtils.toPgVectorLiteral(embeddingProvider.embed(text), embeddingProvider.dimension());
        } catch (Exception e) {
            log.warn("embedding failed, skip vector persistence: {}", e.getMessage());
            return null;
        }
    }

    private void insertChunk(AiCodeChunkEntity chunk) throws Exception {
        try {
            chunkMapper.insert(chunk);
        } catch (Exception e) {
            if (chunk != null && chunk.getEmbeddingVector() != null && isVectorInfrastructureError(e)) {
                vectorPersistenceDisabled = true;
                chunk.setEmbeddingVector(null);
                log.warn("vector persistence disabled for this process, retry chunk insert without embedding: {}", e.getMessage());
                chunkMapper.insert(chunk);
                return;
            }
            throw e;
        }
    }

    private boolean isVectorInfrastructureError(Exception e) {
        String message = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("embedding_vec")
                || message.contains("type \"vector\"")
                || message.contains("type vector")
                || message.contains("operator does not exist")
                || message.contains("does not exist") && message.contains("vector")
                || message.contains("relation") && message.contains("vector")
                || message.contains("column") && message.contains("vector");
    }

    /**
     * 根据已落库的符号切片建立轻量调用关系。
     * <p>
     * 关系只在一次完整索引结束前生成，失败时不会把半成品关系留给后续问答。
     * 目标匹配依赖方法名和目录层级，属于保守的候选图；模型上下文仍会同时看到原始切片。
     */
    private void rebuildRelations(Long projectId) {
        chunkMapper.deleteRelationsByProject(projectId);
        int sources = chunkMapper.countRelationSources(projectId);
        int targets = chunkMapper.countRelationTargets(projectId);
        long candidatePairs = (long) sources * (long) targets;
        long maxPairs = relationMaxPairs();
        if (maxPairs > 0 && candidatePairs > maxPairs) {
            log.warn("skip code graph relations, projectId={}, sources={}, targets={}, candidatePairs={}, maxPairs={}",
                    projectId, sources, targets, candidatePairs, maxPairs);
            return;
        }
        int inserted = 0;
        for (AiCodeRelationCandidate candidate : chunkMapper.findRelationCandidates(projectId)) {
            if (candidate == null || candidate.getSourceChunkId() == null || candidate.getTargetChunkId() == null
                    || candidate.getSourceChunkId().equals(candidate.getTargetChunkId())) {
                continue;
            }
            chunkMapper.insertRelation(snowflakeIdGenerator.nextId(), projectId,
                    candidate.getSourceChunkId(), candidate.getTargetChunkId(), candidate.getRelationType());
            inserted++;
        }
        log.info("code graph relations rebuilt, projectId={}, relations={}", projectId, inserted);
    }

    private long relationMaxPairs() {
        return configuredRelationMaxPairs > 0 ? configuredRelationMaxPairs : 0L;
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
        GitCredential credential = credentialOf(project);
        Exception last = null;
        for (int attempt = 1; attempt <= GIT_CLONE_ATTEMPTS; attempt++) {
            try {
                runCommand(workspace.getParent(), isolatedHome, credential, "git", "clone", "--depth", "1", "--branch",
                        project.getGitBranch(), project.getGitUrl(), workspace.toString());
                return;
            } catch (Exception e) {
                last = e;
                if (attempt >= GIT_CLONE_ATTEMPTS || !isRetryableGitError(e)) {
                    throw e;
                }
                log.warn("Git 克隆遇到瞬时网络错误，将重试 ({}/{}): projectId={}, err={}",
                        attempt, GIT_CLONE_ATTEMPTS - 1, project.getId(), e.getMessage());
                // git 可能已经在工作区写入了半套 .git；重试前清空，避免第二次 clone 因目标非空失败。
                deleteWorkspace(workspace);
                Files.createDirectories(workspace);
                Thread.sleep(1000L * attempt);
            }
        }
        throw last == null ? new IOException("Git 克隆失败") : last;
    }

    private boolean isRetryableGitError(Exception error) {
        String message = error == null || error.getMessage() == null
                ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("connection timed out")
                || message.contains("connection was reset")
                || message.contains("connection refused")
                || message.contains("failed to connect")
                || message.contains("could not resolve host")
                || message.contains("empty reply")
                || message.contains("bad gateway")
                || message.contains("502");
    }

    private String runGitCommand(Path directory, Path isolatedHome, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        return runCommand(directory, isolatedHome, null, command);
    }

    /** 克隆时要注入的按项目凭据（明文，只在内存与子进程环境里存在） */
    private static class GitCredential {
        final String username;
        final String token;

        GitCredential(String username, String token) {
            this.username = username;
            this.token = token;
        }
    }

    /** 解密项目上存的令牌；没有就返回 null（公开仓库走匿名） */
    private GitCredential credentialOf(AiCodeProjectEntity project) {
        String token = cryptoService.decrypt(project.getCredentialEnc());
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        return new GitCredential(resolveCredentialUser(project.getCredentialUser(), project.getGitUrl()), token.trim());
    }

    /**
     * 令牌对应的用户名。
     * <p>
     * 各家规则不同：GitHub 用 {@code x-access-token}、GitLab 用 {@code oauth2}，
     * 而 Gitee 必须填账号名（填 oauth2 会被拒）。所以优先用用户填的，没填才按主机名给默认值。
     */
    private String resolveCredentialUser(String configured, String gitUrl) {
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        String host = gitUrl == null ? "" : gitUrl.toLowerCase(Locale.ROOT);
        if (host.contains("github.")) {
            return "x-access-token";
        }
        return "oauth2";
    }

    /**
     * 给 git 进程一套"不带任何环境凭据、只认本项目凭据"的运行环境。
     * <p>
     * git 默认会自己去找运行机器上的凭据：全局/系统配置里的 credential.helper、
     * {@code ~/.git-credentials}、{@code ~/.ssh}、SSH agent、系统凭据管理器。
     * 这些凭据属于"跑这个服务的人"，与 BizLens 的用户授权毫无关系。
     * 2026-10-07 实测：一个**私有**仓库在项目未配任何凭据的情况下照样被克隆成功，
     * 用的就是本机 {@code ~/.git-credentials} 里那条 github.com 凭据——
     * 等于任何注册用户都能借机器身份读私有代码，与"不向客户泄露源码"的产品定位直接冲突。
     * <p>
     * 所以这里把 HOME / 全局配置 / 系统配置统统指向空处，再按需注入**本项目自己的**凭据：
     * 用 {@code credential.helper} 的 shell 形式从环境变量里取令牌。
     * 刻意不把令牌写进命令行（{@code https://user:token@host/...} 或 {@code -c credential.helper=...token}），
     * 否则它会出现在进程列表里被同机其他用户看到。
     * 没有配置凭据时就是纯匿名——私有仓库直接失败，这是刻意的：宁可失败，也不要静默借用机器身份。
     */
    private void applyIsolatedGitEnv(ProcessBuilder builder, Path isolatedHome, GitCredential credential) {
        Map<String, String> env = builder.environment();

        // HOME 相关的三个变量都改掉：git 找 ~/.gitconfig 和 ~/.git-credentials 靠它们
        String home = isolatedHome.toAbsolutePath().toString();
        env.put("HOME", home);
        env.put("USERPROFILE", home);
        env.put("XDG_CONFIG_HOME", home);

        // 全局配置指向一个不存在的文件（空目录里没有），系统配置直接禁用
        env.put("GIT_CONFIG_GLOBAL", isolatedHome.resolve(".gitconfig").toAbsolutePath().toString());
        env.put("GIT_CONFIG_NOSYSTEM", "1");

        if (credential == null) {
            // 把 credential.helper 显式清空：空值表示重置 helper 列表，
            // 这样即使将来又冒出来一个系统级 helper 也套不上
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "credential.helper");
            env.put("GIT_CONFIG_VALUE_0", "");
        } else {
            // 先重置（key_0 空值），再加本项目自己的 helper（key_1）。顺序有意义：先清后加。
            // helper 是 shell 片段，由 git 用 sh 执行；令牌从环境变量读，不出现在命令行里。
            env.put("GIT_CONFIG_COUNT", "2");
            env.put("GIT_CONFIG_KEY_0", "credential.helper");
            env.put("GIT_CONFIG_VALUE_0", "");
            env.put("GIT_CONFIG_KEY_1", "credential.helper");
            env.put("GIT_CONFIG_VALUE_1",
                    "!f() { echo username=" + credential.username + "; echo password=\"$BIZLENS_GIT_TOKEN\"; }; f");
            env.put("BIZLENS_GIT_TOKEN", credential.token);
        }

        // 不允许交互，也不给 askpass 兜底程序：要么用上面注入的凭据，要么就失败
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.remove("GIT_ASKPASS");
        env.remove("SSH_ASKPASS");
        // SSH agent 同样是"机器身份"，一并摘掉
        env.remove("SSH_AUTH_SOCK");
        env.remove("GIT_SSH_COMMAND");

        // ===== 代理：一律不继承机器上的设置，只认显式的服务端配置 =====
        //
        // 为什么不能继承：机器上（尤其开发者桌面）常见 Clash 之类的代理只覆盖外网，
        // 而 git 判断"哪些主机不走代理"的规则通常写在全局 git 配置里
        // （http.noproxy / http.<url>.proxy）——那份配置恰恰被本方法清空了。
        // 结果内网 Git 被送进代理，报 502 Bad Gateway（实测 git.inksdev.com 就是这个问题）。
        // 反过来完全不继承、又不去代理，外网 Git（实测 github.com）在本机连不通。
        // 两个方向都得可配，所以改成显式声明：默认直连，需要时由运维在服务端设置
        //   BIZLENS_GIT_PROXY      出网代理，例如 http://proxy.corp:8080
        //   BIZLENS_GIT_NO_PROXY   不走代理的地址，例如 git.inksdev.com,192.168.99.92
        for (String key : new String[]{"HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy",
                "ALL_PROXY", "all_proxy", "NO_PROXY", "no_proxy"}) {
            env.remove(key);
        }
        String gitProxy = trimToNull(System.getenv("BIZLENS_GIT_PROXY"));
        if (gitProxy != null) {
            env.put("HTTP_PROXY", gitProxy);
            env.put("HTTPS_PROXY", gitProxy);
            env.put("http_proxy", gitProxy);
            env.put("https_proxy", gitProxy);
        }
        String gitNoProxy = trimToNull(System.getenv("BIZLENS_GIT_NO_PROXY"));
        if (gitNoProxy != null) {
            env.put("NO_PROXY", gitNoProxy);
            env.put("no_proxy", gitNoProxy);
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String runCommand(Path directory, Path isolatedHome, GitCredential credential, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true);
        applyIsolatedGitEnv(builder, isolatedHome, credential);
        Process process = builder.start();
        boolean done = process.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
        String output;
        try (InputStream input = process.getInputStream()) {
            output = new String(readAll(input), StandardCharsets.UTF_8);
        }
        if (!done) {
            throw new IOException("Git 命令执行超时（" + GIT_COMMAND_TIMEOUT_SECONDS + " 秒），已终止进程");
        }
        if (process.exitValue() != 0) {
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
     * 鉴权类失败要分两种情况给出可操作的说明：配了令牌被拒（令牌/用户名不对），
     * 和压根没配令牌（私有仓库需要令牌）。混为一谈的话用户只会反复重试。
     * 注意克隆始终不借用运行机器的凭据，所以"没配令牌"时必然失败，这是刻意的。
     */
    private String buildFailureReason(AiCodeProjectEntity project, Exception e) {
        String reason = sanitizeGitError(e);
        String lower = reason.toLowerCase(Locale.ROOT);
        // SSH 地址在任何失败情况下都给同一条说明：隔离环境不继承 ~/.ssh（密钥、known_hosts），
        // 也不支持部署密钥，所以 SSH 地址当前必然失败——说清楚比让用户去查密钥有用得多。
        String url = project == null ? "" : (project.getGitUrl() == null ? "" : project.getGitUrl());
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "SSH 地址当前无法克隆：克隆在隔离环境里执行，不继承运行机器的 SSH 密钥与 known_hosts，"
                    + "也尚未支持部署密钥。请改用 HTTPS 地址（私有仓库配合访问令牌）。原始错误：" + reason;
        }
        // 连不上：多半是出口网络问题。克隆不继承机器的代理设置，外网 Git 若需要代理出网，
        // 必须在服务端显式配置——这条不说清楚，用户只会以为是地址写错了。
        boolean networkFailure = lower.contains("could not resolve host")
                || lower.contains("connection was reset")
                || lower.contains("failed to connect")
                || lower.contains("connection timed out")
                || lower.contains("connection refused")
                || lower.contains("empty reply")
                || lower.contains("bad gateway")
                || lower.contains("502");
        if (networkFailure) {
            return "无法连接到 Git 服务器：先确认地址与网络；若该地址在外网（如 github.com）而本服务需要经代理出网，"
                    + "请在服务端设置 BIZLENS_GIT_PROXY（必要时用 BIZLENS_GIT_NO_PROXY 排除内网地址）"
                    + "——克隆不会继承运行机器的代理设置。原始错误：" + reason;
        }
        // 分支名不对是最常见的配置失误：仓库默认分支可能是 main 也可能是 master，
        // 直接点明"是分支的问题"，比让用户对着含糊的 not found 猜要好
        if (lower.contains("remote branch") && lower.contains("not found")) {
            return "分支不存在：仓库里没有配置的这个分支，请把「分支」改成正确的名字"
                    + "（Git 默认分支可能是 main，也可能是 master）。原始错误：" + reason;
        }
        boolean authFailure = lower.contains("terminal prompts disabled")
                || lower.contains("could not read username")
                || lower.contains("could not read password")
                || lower.contains("authentication failed")
                || lower.contains("invalid username or password")
                || lower.contains("permission denied")
                || lower.contains("publickey")
                || lower.contains("403");
        if (authFailure) {
            boolean hasToken = project != null && project.getCredentialEnc() != null
                    && !project.getCredentialEnc().trim().isEmpty();
            if (hasToken) {
                return "访问令牌被仓库拒绝：请确认令牌未过期、有该仓库的读取权限，且用户名填对"
                        + "（GitHub 用 x-access-token、GitLab 用 oauth2、Gitee 必须填账号名）；原始错误：" + reason;
            }
            return "该仓库需要凭据：请在项目里填写访问令牌（支持 GitHub / Gitee / GitLab 及自建 GitLab 的私有仓库）。"
                    + "克隆不会借用运行机器的 Git 凭据，未配令牌时私有仓库必然失败；原始错误：" + reason;
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

    /** @return 是否确实删掉了（失败时残留交给下次启动的清理兜底） */
    private boolean deleteWorkspace(Path workspace) {
        if (workspace == null) return false;
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { deleteReadOnlyAware(file); return FileVisitResult.CONTINUE; }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { deleteReadOnlyAware(dir); return FileVisitResult.CONTINUE; }
            });
            return true;
        } catch (Exception e) {
            // 打印异常类名：AccessDeniedException 的 getMessage() 只有路径，不打类名根本看不出是权限问题
            log.warn("删除索引临时工作区失败（残留会在下次启动时清理）: {} - {}: {}",
                    workspace, e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /**
     * 删除文件或目录，删之前先清掉只读属性。
     * <p>
     * 这是 Windows 上一个必须处理的坑：**git 会把 {@code .git/objects/pack} 下的
     * .pack/.idx/.rev 设成只读**，而 {@code Files.deleteIfExists} 遇到只读文件会直接抛
     * AccessDeniedException（不像 rm 那样会先 chmod）。结果是目录删不空、整棵工作区
     * 都留在临时目录里——实测累积了 25 个目录、309MB，最早的是两周前，说明清理**从来没成功过**。
     * Linux 上这句是无操作。
     */
    private void deleteReadOnlyAware(Path path) throws IOException {
        File file = path.toFile();
        if (!file.canWrite()) {
            file.setWritable(true);
        }
        Files.deleteIfExists(path);
    }

    private static class IndexCounter {
        private int visited;
        private int processed;
        private int indexed;
        private int skipped;
    }

    private void throwIfCancelRequested(Long projectId) {
        if (projectId == null) return;
        if (CANCEL_REQUESTS.contains(projectId)) {
            throw new IndexCancelledException("索引任务已由用户手动终止，可重新排队索引");
        }
        AiCodeProjectEntity latest = projectMapper.getById(projectId);
        if (latest != null && "FAILED".equals(latest.getStatus())
                && latest.getLastError() != null && latest.getLastError().contains("手动终止")) {
            throw new IndexCancelledException(latest.getLastError());
        }
    }

    private static class IndexCancelledException extends RuntimeException {
        IndexCancelledException(String message) {
            super(message);
        }
    }

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
