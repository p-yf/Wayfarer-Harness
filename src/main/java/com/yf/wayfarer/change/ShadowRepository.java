package com.yf.wayfarer.change;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单个工作区对应的影子 git 仓库。
 *
 * @author yyf
 * @description git-dir 放在工作区之外，work-tree 指向工作区，因此不会在工作区里生成 .git。
 * 所有读写操作都按仓库加锁串行执行；每次工具调用产生的改动对应一条提交，
 * 提交说明里的 Wayfarer-* trailer 记录会话、轮次与工具调用信息。
 */
public final class ShadowRepository {

    private static final Logger log = LoggerFactory.getLogger(ShadowRepository.class);

    /**
     * 影子仓库的提交作者固定，便于和用户自己的提交区分。
     */
    private static final String AUTHOR = "Wayfarer";
    private static final String AUTHOR_EMAIL = "wayfarer@local";

    /**
     * 提交说明里 trailer 的键前缀。
     */
    static final String TRAILER_SESSION = "Wayfarer-Session";
    static final String TRAILER_TRACE = "Wayfarer-Trace";
    static final String TRAILER_TOOL = "Wayfarer-Tool";
    static final String TRAILER_CALL = "Wayfarer-Call";
    static final String TRAILER_STATUS = "Wayfarer-Status";
    static final String TRAILER_REASON = "Wayfarer-Reason";
    static final String TRAILER_REVERTS = "Wayfarer-Reverts";

    /**
     * 基线提交的工具名，基线提交不参与撤销。
     */
    static final String BASELINE_TOOL = "baseline";

    private final Path root;
    private final Path gitDir;
    private final Path workTree;
    private final List<String> excludes;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * @param root     该仓库在影子根目录下的文件夹
     * @param gitDir   git 元数据目录，位于工作区之外
     * @param workTree 工作区目录
     */
    ShadowRepository(Path root, Path gitDir, Path workTree, List<String> excludes) {
        this.root = root;
        this.gitDir = gitDir;
        this.workTree = workTree;
        this.excludes = List.copyOf(excludes);
    }

    public Path workTree() {
        return workTree;
    }

    public Path root() {
        return root;
    }

    /**
     * 确保仓库存在：首次调用时创建 git-dir、写入排除规则并做一次基线提交。
     * 之后每次调用都会刷新排除规则，改动配置后无需手工清理仓库。
     */
    public void ensure() {
        locked(() -> {
            boolean created = !Files.isRegularFile(gitDir.resolve(Constants.HEAD));
            if (created) {
                try {
                    Files.createDirectories(gitDir);
                    try (Repository repository = openRepository()) {
                        repository.create(false);
                        // git-dir 与 work-tree 分离后必须显式记住工作区位置，否则后续打开找不到工作区
                        StoredConfig config = repository.getConfig();
                        config.setString("core", null, "worktree", workTree.toString());
                        config.save();
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("创建影子仓库失败：" + gitDir, e);
                }
            }
            writeExcludes();
            if (created) {
                baseline();
                log.info("影子仓库已创建 workTree={} gitDir={}", workTree, gitDir);
            }
            return null;
        });
    }

    /**
     * 当前工作区里所有「与上次提交不一致」的路径（含未跟踪、已删除），已按排除规则过滤。
     * 这是归因的基础：工具执行前后各取一次，差集就是本次调用真正改动的文件。
     */
    public Set<String> dirtyPaths() {
        return locked(() -> {
            try (Git git = newGit()) {
                Status status = git.status().call();
                // 显式合并各类状态：新增、修改、删除、缺失、冲突、未跟踪，
                // 不能只用 getUncommittedChanges，否则新创建的文件可能被漏掉
                Set<String> dirty = new TreeSet<>();
                dirty.addAll(status.getAdded());
                dirty.addAll(status.getChanged());
                dirty.addAll(status.getModified());
                dirty.addAll(status.getRemoved());
                dirty.addAll(status.getMissing());
                dirty.addAll(status.getConflicting());
                dirty.addAll(status.getUntracked());
                return dirty;
            } catch (GitAPIException e) {
                throw new IllegalStateException("读取工作区状态失败", e);
            }
        });
    }

    /**
     * 提交指定路径的改动。
     *
     * @return 提交 id；没有实际内容变化时返回 null
     */
    public String commit(Set<String> paths, String summary, Map<String, String> trailers) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        return locked(() -> {
            try (Git git = newGit()) {
                stage(git, paths);
                try {
                    RevCommit commit = git.commit()
                            .setMessage(message(summary, trailers))
                            .setAuthor(identity())
                            .setCommitter(identity())
                            .call();
                    return commit.getName();
                } catch (EmptyCommitException e) {
                    // 路径变了但内容与上次一致（例如写回同样内容），不需要留提交
                    return null;
                }
            } catch (GitAPIException e) {
                throw new IllegalStateException("写入影子仓库失败", e);
            }
        });
    }

    /**
     * 全部提交，新的在前。调用方按会话、轮次过滤后使用。
     */
    public List<ChangeCommit> commits() {
        return locked(() -> {
            try (Git git = newGit(); RevWalk walk = new RevWalk(git.getRepository())) {
                ObjectId head = git.getRepository().resolve(Constants.HEAD);
                if (head == null) {
                    return List.of();
                }
                walk.markStart(walk.parseCommit(head));
                List<ChangeCommit> commits = new ArrayList<>();
                for (RevCommit commit : walk) {
                    commits.add(toChangeCommit(commit));
                }
                return commits;
            } catch (IOException e) {
                throw new UncheckedIOException("读取影子仓库提交失败", e);
            }
        });
    }

    /**
     * 该提交相对父提交改动的路径。
     */
    public Set<String> changedPaths(String commitId) {
        return locked(() -> {
            Set<String> paths = new LinkedHashSet<>();
            for (DiffEntry entry : entries(commitId, null)) {
                if (entry.getChangeType() == DiffEntry.ChangeType.DELETE) {
                    paths.add(entry.getOldPath());
                } else if (entry.getChangeType() == DiffEntry.ChangeType.RENAME) {
                    paths.add(entry.getOldPath());
                    paths.add(entry.getNewPath());
                } else {
                    paths.add(entry.getNewPath());
                }
            }
            return paths;
        });
    }

    /**
     * 该提交的变更文件列表（含增删行数），用于列表接口展示。
     */
    public List<ChangeFile> files(String commitId) {
        return locked(() -> {
            List<ChangeFile> files = new ArrayList<>();
            try (Git git = newGit(); RevWalk walk = new RevWalk(git.getRepository())) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
                List<DiffEntry> entries;
                try (DiffFormatter scanner = newFormatter(git.getRepository(), null)) {
                    entries = scan(scanner, walk, commit);
                }
                for (DiffEntry entry : entries) {
                    String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE
                            ? entry.getOldPath() : entry.getNewPath();
                    Patch patch = patchOf(git.getRepository(), entry);
                    files.add(new ChangeFile(path, entry.getChangeType().name(),
                            patch.insertions(), patch.deletions(), patch.binary()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("统计变更文件失败", e);
            }
            return files;
        });
    }

    /**
     * 该提交的 unified diff 文本；path 为空时返回本次提交全部文件的补丁。
     */
    public String diff(String commitId, String path) {
        return locked(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Git git = newGit();
                 RevWalk walk = new RevWalk(git.getRepository());
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(git.getRepository());
                formatter.setDetectRenames(false);
                if (path != null) {
                    formatter.setPathFilter(PathFilter.create(path));
                }
                RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
                List<DiffEntry> entries = scan(formatter, walk, commit);
                if (entries.isEmpty()) {
                    return "";
                }
                formatter.format(entries);
                formatter.flush();
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("生成 diff 失败", e);
            }
        });
    }

    /**
     * 仓库里是否存在该提交，用于按提交 id 反查所属工作区。
     */
    public boolean contains(String commitId) {
        if (commitId == null || commitId.isBlank()) {
            return false;
        }
        return locked(() -> {
            try (Git git = newGit(); RevWalk walk = new RevWalk(git.getRepository())) {
                walk.parseCommit(ObjectId.fromString(commitId));
                return true;
            } catch (IOException | IllegalArgumentException e) {
                return false;
            }
        });
    }

    /**
     * 撤销若干提交（按传入顺序，新的在前）。
     *
     * <p>做法是把这些提交涉及的路径恢复到各自父提交的内容，最后合并成一条回退提交。
     * 之所以不改写历史：仓库被多个会话共享，改写会连带抹掉别人的提交。</p>
     *
     * @param commitIds       要撤销的提交，新的在前
     * @param pathFilter      只撤销单个路径，null 表示全部
     * @param summary         回退提交的摘要
     * @param trailers        回退提交的 trailer
     */
    public RevertOutcome revert(List<String> commitIds, String pathFilter, String summary,
                                Map<String, String> trailers) {
        return locked(() -> {
            // expected：该路径「最后一次被撤销提交改动后」的内容，用来判断工作区是否被别人改过
            Map<String, byte[]> expected = new LinkedHashMap<>();
            // desired：回退后该路径应有的内容，null 表示删除；旧提交后处理，自然收敛到最早提交的父内容
            Map<String, byte[]> desired = new LinkedHashMap<>();
            for (String commitId : commitIds) {
                for (String path : changedPaths(commitId)) {
                    if (pathFilter != null && !pathFilter.equals(path)) {
                        continue;
                    }
                    expected.putIfAbsent(path, contentIn(commitId, path));
                    desired.put(path, contentInParent(commitId, path));
                }
            }

            List<Map<String, Object>> conflicts = new ArrayList<>();
            List<String> applied = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : desired.entrySet()) {
                String path = entry.getKey();
                if (!sameAsWorkTree(path, expected.get(path))) {
                    conflicts.add(conflict(path, "被其它会话或用户修改"));
                    continue;
                }
                writeWorkTreeFile(path, entry.getValue());
                applied.add(path);
            }

            String commitId = applied.isEmpty()
                    ? null
                    : commit(new LinkedHashSet<>(applied), summary, trailers);
            return new RevertOutcome(commitId, applied, conflicts);
        });
    }

    /**
     * 把工具参数里的路径转换成相对工作区的路径，路径在工作区之外时返回 null。
     */
    public String relativePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path target = Path.of(rawPath);
        if (!target.isAbsolute()) {
            target = workTree.resolve(rawPath);
        }
        Path normalized = target.normalize();
        if (!normalized.startsWith(workTree)) {
            return null;
        }
        return workTree.relativize(normalized).toString().replace('\\', '/');
    }

    /**
     * 创建仓库对象。git-dir 与 work-tree 分离，这是「不污染工作区」的关键。
     */
    private Repository openRepository() {
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(gitDir.toFile())
                    .setWorkTree(workTree.toFile())
                    .setMustExist(false)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("打开影子仓库失败：" + gitDir, e);
        }
    }

    private Git newGit() {
        return new Git(openRepository());
    }

    /**
     * 基线提交：对工作区做一次全量快照，作为之后所有差异的起点。
     */
    private void baseline() {
        try (Git git = newGit()) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage(message("baseline", Map.of(TRAILER_TOOL, BASELINE_TOOL)))
                    .setAuthor(identity())
                    .setCommitter(identity())
                    .setAllowEmpty(true)
                    .call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("创建基线提交失败", e);
        }
    }

    /**
     * 排除规则写进 git-dir 里的 info/exclude：只影响影子仓库，不会修改工作区的 .gitignore。
     */
    private void writeExcludes() {
        StringBuilder builder = new StringBuilder();
        for (String exclude : excludes) {
            builder.append(exclude).append('\n');
        }
        Path file = gitDir.resolve("info").resolve("exclude");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, builder.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写入排除规则失败：" + file, e);
        }
    }

    /**
     * 暂存指定路径：存在的文件用 add，已删除的文件用 add --update（等价于 git rm 的暂存效果）。
     */
    private void stage(Git git, Set<String> paths) throws GitAPIException {
        for (String path : paths) {
            if (Files.exists(workTree.resolve(path))) {
                git.add().addFilepattern(path).call();
            } else {
                git.add().setUpdate(true).addFilepattern(path).call();
            }
        }
    }

    private List<DiffEntry> entries(String commitId, String path) {
        try (Git git = newGit();
             RevWalk walk = new RevWalk(git.getRepository());
             DiffFormatter formatter = newFormatter(git.getRepository(), path)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
            return scan(formatter, walk, commit);
        } catch (IOException e) {
            throw new UncheckedIOException("读取提交变更失败：" + commitId, e);
        }
    }

    /**
     * 只用于 scan 的格式化器：输出流在构造时固定，scan 本身不写内容。
     */
    private DiffFormatter newFormatter(Repository repository, String path) {
        DiffFormatter formatter = new DiffFormatter(new ByteArrayOutputStream());
        formatter.setRepository(repository);
        formatter.setDetectRenames(false);
        if (path != null) {
            formatter.setPathFilter(PathFilter.create(path));
        }
        return formatter;
    }

    /**
     * 扫描提交与父提交的差异；没有父提交（基线）时与空树比较。
     */
    private List<DiffEntry> scan(DiffFormatter formatter, RevWalk walk, RevCommit commit) throws IOException {
        RevTree tree = commit.getTree();
        List<DiffEntry> entries;
        if (commit.getParentCount() == 0) {
            entries = formatter.scan(new EmptyTreeIterator(), treeIterator(walk, tree));
        } else {
            RevCommit parent = walk.parseCommit(commit.getParent(0));
            entries = formatter.scan(treeIterator(walk, parent.getTree()), treeIterator(walk, tree));
        }
        return entries;
    }

    private CanonicalTreeParser treeIterator(RevWalk walk, RevTree tree) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(walk.getObjectReader(), tree);
        return parser;
    }

    /**
     * 把单个文件的差异格式化成文本，并统计增删行数与是否为二进制。
     * 输出流在构造时固定，所以每个文件用一个独立的格式化器。
     */
    private Patch patchOf(Repository repository, DiffEntry entry) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(false);
            formatter.format(entry);
            formatter.flush();
        }
        String text = out.toString(StandardCharsets.UTF_8);
        int insertions = 0;
        int deletions = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                insertions++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deletions++;
            }
        }
        return new Patch(insertions, deletions, text.contains("Binary files"));
    }

    /**
     * 读取某个提交里某文件的内容；文件不在该提交中时返回 null。
     */
    private byte[] contentIn(String commitId, String path) {
        try (Git git = newGit(); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
            return contentIn(git.getRepository(), commit.getTree(), path);
        } catch (IOException e) {
            throw new UncheckedIOException("读取提交内容失败：" + commitId, e);
        }
    }

    /**
     * 读取某个提交的父提交里某文件的内容；没有父提交或父提交里没有该文件时返回 null。
     */
    private byte[] contentInParent(String commitId, String path) {
        try (Git git = newGit(); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
            if (commit.getParentCount() == 0) {
                return null;
            }
            RevCommit parent = walk.parseCommit(commit.getParent(0));
            return contentIn(git.getRepository(), parent.getTree(), path);
        } catch (IOException e) {
            throw new UncheckedIOException("读取父提交内容失败：" + commitId, e);
        }
    }

    private static byte[] contentIn(Repository repository, RevTree tree, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repository, path, tree)) {
            if (walk == null) {
                return null;
            }
            ObjectId id = walk.getObjectId(0);
            if (id == null) {
                return null;
            }
            ObjectLoader loader = repository.open(id);
            return loader.getBytes();
        }
    }

    /**
     * 工作区当前内容是否仍等于期望内容（null 表示期望文件不存在）。
     */
    private boolean sameAsWorkTree(String path, byte[] expected) {
        Path file = workTree.resolve(path);
        if (expected == null) {
            return !Files.exists(file);
        }
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return Arrays.equals(Files.readAllBytes(file), expected);
        } catch (IOException e) {
            return false;
        }
    }

    private void writeWorkTreeFile(String path, byte[] content) {
        Path file = workTree.resolve(path);
        try {
            if (content == null) {
                Files.deleteIfExists(file);
                return;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("恢复文件失败：" + path, e);
        }
    }

    private ChangeCommit toChangeCommit(RevCommit commit) {
        Map<String, String> trailers = trailers(commit.getFullMessage());
        String summary = commit.getFullMessage().split("\n", 2)[0].trim();
        return new ChangeCommit(
                commit.getName(),
                commit.getParentCount() == 0 ? null : commit.getParent(0).getName(),
                trailers.get(TRAILER_SESSION),
                trailers.get(TRAILER_TRACE),
                trailers.get(TRAILER_TOOL),
                trailers.get(TRAILER_CALL),
                summary,
                Instant.ofEpochSecond(commit.getCommitTime()));
    }

    /**
     * 提交说明由「摘要 + 空行 + trailer」组成，这里把 trailer 解析成 map。
     */
    static Map<String, String> trailers(String message) {
        Map<String, String> trailers = new LinkedHashMap<>();
        if (message == null) {
            return trailers;
        }
        for (String line : message.split("\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (key.startsWith("Wayfarer-")) {
                trailers.put(key, line.substring(separator + 1).trim());
            }
        }
        return trailers;
    }

    static String message(String summary, Map<String, String> trailers) {
        StringBuilder builder = new StringBuilder(summary == null ? "" : summary);
        if (trailers != null && !trailers.isEmpty()) {
            builder.append("\n\n");
            trailers.forEach((key, value) -> builder.append(key).append(": ").append(value).append('\n'));
        }
        return builder.toString();
    }

    private static PersonIdent identity() {
        return new PersonIdent(AUTHOR, AUTHOR_EMAIL);
    }

    private static Map<String, Object> conflict(String path, String reason) {
        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("path", path);
        conflict.put("reason", reason);
        return conflict;
    }

    /**
     * 统一加锁入口：同一仓库的读写必须串行，否则 index 会被并发改写。
     * 等锁最多 5 秒，超时说明另一个会话正在长时间操作该工作区，直接放弃本次记录。
     */
    private <T> T locked(Supplier<T> action) {
        boolean acquired;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待影子仓库锁被中断", e);
        }
        if (!acquired) {
            throw new IllegalStateException("影子仓库忙，本次记录已跳过：" + workTree);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @author yyf
     * @description 单个文件的补丁统计结果
     */
    private record Patch(int insertions, int deletions, boolean binary) {
    }
}
