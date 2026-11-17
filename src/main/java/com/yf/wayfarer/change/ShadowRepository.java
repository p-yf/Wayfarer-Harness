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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 单个工作区对应的影子 git 仓库。
 *
 * <p>理解下面五点就能看懂这个类：</p>
 *
 * <p><b>1. 仓库放在哪。</b>git 元数据（objects、refs、index）放在工作区之外的
 * {@code <changes.root>/<key>/git-dir}，只有 work-tree 指向用户工作区。
 * 这样用户目录里不会冒出多余的 .git，也不会覆盖用户自己在用的版本库。
 * config 里写死 core.worktree 是必须的：git-dir 与 work-tree 分离后，
 * 下次打开仓库时 git 无法自己推断工作区在哪。</p>
 *
 * <p><b>2. 基线提交。</b>仓库第一次创建时先对工作区做一次全量提交（trailer 里
 * {@code Wayfarer-Tool=baseline}），代表「Agent 动手之前的样子」。之后所有 diff、
 * 撤销都以它为起点，所以基线提交不允许撤销。</p>
 *
 * <p><b>3. 一轮运行 = 一条提交。</b>{@code ChangeRecorder} 在运行开始与运行收尾各取一次
 * {@link #dirtyPaths()}，差集就是这次运行真正改动的文件；收尾时调用 {@link #commit}。
 * 提交时只 stage 这几个路径，运行前用户自己就改脏的文件不会被误记成 Agent 的改动。</p>
 *
 * <p><b>4. 用提交说明做索引。</b>不引入数据库：提交说明第一行是摘要，空行之后是
 * {@code Wayfarer-Session / Trace / Tool} 等 trailer。查询时遍历提交、解析
 * trailer 就能按会话或轮次过滤，也能反查某次改动属于哪个会话、哪个轮次。</p>
 *
 * <p><b>5. 撤销不是改写历史。</b>仓库被同一工作区的多个会话共享，所以既不能
 * {@code reset --hard} 也不能整树 checkout，否则会连带抹掉别的会话的记录和用户的手工修改。
 * 撤销的做法是：把目标提交涉及的路径恢复成它<b>父提交</b>里的内容，再生成一条新的回退提交。</p>
 *
 * <p>所有对仓库的读写都经过 {@link #locked} 串行化，因为 JGit 的 index 是共享文件，
 * 并发 add/commit 会把它写坏。</p>
 *
 * @author yyf
 * @description 影子仓库封装：创建与基线、脏文件快照、按路径提交、提交查询、diff、撤销
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
     * 两个提交之间的 unified diff 文本，只保留 path 一个文件。
     *
     * <p>用于「按文件看整轮改动」：页面拿到某轮最早那次改动的父提交、最晚那次改动，
     * 就能得到这个文件在整轮里的净变化，而不是分散在每次工具调用里的碎片补丁。</p>
     *
     * @param baseCommitId 基准提交，null 表示空树（用于基线提交这种没有父提交的情况）
     * @param targetCommitId 目标提交
     */
    public String diffRange(String baseCommitId, String targetCommitId, String path) {
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
                RevCommit target = walk.parseCommit(ObjectId.fromString(targetCommitId));
                var base = baseCommitId == null
                        ? new EmptyTreeIterator()
                        : treeIterator(walk, walk.parseCommit(ObjectId.fromString(baseCommitId)).getTree());
                List<DiffEntry> entries = formatter.scan(base, treeIterator(walk, target.getTree()));
                if (entries.isEmpty()) {
                    return "";
                }
                formatter.format(entries);
                formatter.flush();
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("生成区间 diff 失败", e);
            }
        });
    }

    /**
     * 撤销若干提交（按传入顺序，新的在前）。
     *
     * <p>做法是把这些提交涉及的路径恢复到各自父提交的内容，最后合并成一条回退提交。
     * 之所以不改写历史：仓库被多个会话共享，改写会连带抹掉别人的提交。</p>
     *
     * <p>举例：某文件先后被 c1、c2 改过，现在要撤销这两次。逆序处理时先记下 c2 的
     * 父内容作为「目标值」、c1 的父内容再覆盖它；于是同一路径多次改动会自然收敛到
     * 最早那次改动之前的状态。</p>
     *
     * <p>写回时直接以目标内容覆盖工作区：即使这期间用户或其它会话也改过该文件，
     * 也按「撤销优先」处理，不做冲突跳过。覆盖前的旧内容仍留在 shadow 仓库的历史里，
     * 随时可以从 diff 面板看回来。</p>
     *
     * <p>文件删完后，按提交树判断哪些目录是本轮新建的，把整棵目录删掉：
     * git 不记录目录，只删文件会在工作区留下空文件夹和未提交残留。</p>
     *
     * @param commitIds       要撤销的提交，新的在前
     * @param pathFilter      只撤销单个路径，null 表示全部
     * @param summary         回退提交的摘要
     * @param trailers        回退提交的 trailer
     */
    public RevertOutcome revert(List<String> commitIds, String pathFilter, String summary,
                                Map<String, String> trailers) {
        return locked(() -> {
            // desired：回退后该路径应有的内容，null 表示删除；旧提交后处理，自然收敛到最早提交的父内容
            Map<String, byte[]> desired = new LinkedHashMap<>();
            for (String commitId : commitIds) {
                for (String path : changedPaths(commitId)) {
                    if (pathFilter != null && !pathFilter.equals(path)) {
                        continue;
                    }
                    desired.put(path, contentInParent(commitId, path));
                }
            }

            List<String> applied = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            for (Map.Entry<String, byte[]> entry : desired.entrySet()) {
                writeWorkTreeFile(entry.getKey(), entry.getValue());
                applied.add(entry.getKey());
                if (entry.getValue() == null) {
                    removed.add(entry.getKey());
                }
            }
            // 文件删掉之后，本轮新建的目录会留下空壳或未提交残留，按提交记录一并删除
            removeCreatedDirectories(commitIds, removed);

            String commitId = applied.isEmpty()
                    ? null
                    : commit(new LinkedHashSet<>(applied), summary, trailers);
            return new RevertOutcome(commitId, applied);
        });
    }

    /**
     * 按提交记录删除本轮新建的目录。
     *
     * <p>git 只记录文件、不记录目录，所以「目录是不是本轮建的」由提交树推断：
     * 把被删文件逐级父目录拿去和最早一条被撤销提交的父提交（回退基准）比较，
     * 第一个在基准树里不存在的目录就是本轮新建目录的根，整棵删除；
     * 基准树里已经存在的目录说明是之前就有的，一律不动。</p>
     *
     * <p>按目录整体删除而不是「空了才删」：目录里可能有没进过提交的残留
     * （例如子 Agent 在轮次收尾后写入的文件），只删记录过的文件会留下空壳和残骸。</p>
     */
    private void removeCreatedDirectories(List<String> commitIds, List<String> removedPaths) {
        if (commitIds.isEmpty() || removedPaths.isEmpty()) {
            return;
        }
        // commitIds 新的在前，最后一条是最早的提交，它的父提交就是回退的基准状态
        String oldest = commitIds.get(commitIds.size() - 1);
        try (Git git = newGit(); RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(oldest));
            RevTree baseTree = commit.getParentCount() == 0
                    ? null : walk.parseCommit(commit.getParent(0)).getTree();
            for (String path : removedPaths) {
                String[] parts = path.split("/");
                StringBuilder prefix = new StringBuilder();
                // 逐级检查父目录，找到第一个在基准树里不存在的祖先
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) {
                        prefix.append('/');
                    }
                    prefix.append(parts[i]);
                    String relative = prefix.toString();
                    if (baseTree != null && TreeWalk.forPath(git.getRepository(), relative, baseTree) != null) {
                        continue;
                    }
                    Path directory = workTree.resolve(relative);
                    if (Files.isDirectory(directory)) {
                        deleteRecursively(directory);
                        log.info("撤销本轮新建目录 workTree={} path={}", workTree, relative);
                    }
                    break;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("清理本轮新建目录失败：" + workTree, e);
        }
    }

    /**
     * 递归删除目录，先删子项再删目录本身。
     */
    private static void deleteRecursively(Path directory) throws IOException {
        List<Path> entries;
        try (Stream<Path> walk = Files.walk(directory)) {
            entries = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path entry : entries) {
            Files.deleteIfExists(entry);
        }
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
