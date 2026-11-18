package com.yf.wayfarer.rsi;

import com.yf.wayfarer.rsi.model.RsiCommit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * RSI 沉淀仓库的 git 服务。
 *
 * <p>每个 (agent, kind) 一个独立仓库：git-dir 放在 {@code .wayfarer/rsi/git/<agent>/<kind>.git}，
 * worktree 即对应 store 目录（{@code stores/<agent>/skills|memory}），两者分离——
 * 结构与 change 包的 {@code ShadowRepository} 同构，理解 ShadowRepository 就能看懂这里。</p>
 *
 * <p>与 ShadowRepository 的三点关键差异：</p>
 * <ol>
 *   <li><b>回溯语义</b>：这里直接 {@code reset --hard} 移动分支指针。因为 store 目录内
 *       只有沉淀流水线一个写者、没有人类编辑，回溯不存在「覆盖别人改动」的问题；
 *       回溯点之后的提交按设计丢弃（不可达，交给 gc），回溯后从回溯点线性继续提交。</li>
 *   <li><b>提交粒度</b>：一次沉淀 = 一次 commit，message 首行是摘要、空行后跟
 *       {@code Rsi-*} trailer（来源会话、层、trace），历史即台账。</li>
 *   <li><b>基线</b>：仓库首次创建时做一次空树基线提交（message=baseline），
 *       之后所有 diff、回溯都以它为起点。</li>
 * </ol>
 *
 * <p>并发控制：JGit 的 index 是共享文件，同一仓库的并发 add/commit 会把它写坏，
 * 因此按 (agent, kind) 维度用 ReentrantLock 串行化全部读写。</p>
 *
 * @author yyf
 * @description RSI 回溯设施：仓库创建与基线、按路径提交、提交查询（含 trailer 解析）、
 * diff（单提交与任意两点）、reset --hard 回溯、按版本读文件与文件树
 */
@Service
public class RsiGitService {

    private static final Logger log = LoggerFactory.getLogger(RsiGitService.class);

    /** 提交作者固定，便于与用户自己的提交区分。 */
    private static final String AUTHOR = "rsi-agent";
    private static final String AUTHOR_EMAIL = "rsi@wayfarer";

    /** commit message trailer 键：kind / agent / 来源会话 / trace / 沉淀层。 */
    public static final String TRAILER_KIND = "Rsi-Kind";
    public static final String TRAILER_AGENT = "Rsi-Agent";
    public static final String TRAILER_SOURCE_SESSION = "Rsi-Source-Session";
    public static final String TRAILER_TRACE_ID = "Rsi-Trace-Id";
    public static final String TRAILER_LAYER = "Rsi-Layer";

    private final RsiPaths paths;

    /** (agent:kind) -> 仓库锁：同一仓库的读写必须串行。 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public RsiGitService(RsiPaths paths) {
        this.paths = paths;
    }

    /**
     * 确保仓库存在：首次调用时创建 git-dir、写入 core.worktree 并做一次空树基线提交。
     * 之后重复调用直接返回（幂等）。
     */
    public void ensure(String agent, String kind) {
        paths.agent(agent);
        paths.kind(kind);
        locked(agent, kind, () -> {
            Path gitDir = paths.gitDir(agent, kind);
            if (!Files.isRegularFile(gitDir.resolve(Constants.HEAD))) {
                try {
                    Files.createDirectories(gitDir);
                    // worktree 目录也可能尚不存在（首个沉淀还没落盘），一并建好
                    Files.createDirectories(paths.kindDir(agent, kind));
                    try (Repository repository = openRepository(gitDir, paths.kindDir(agent, kind))) {
                        repository.create(false);
                        // git-dir 与 work-tree 分离后必须显式记住工作区位置，否则下次打开找不到
                        StoredConfig config = repository.getConfig();
                        config.setString("core", null, "worktree", paths.kindDir(agent, kind).toString());
                        // 关闭换行转换：store 内容全部是机器写入的 JSON/Markdown，
                        // autocrlf 会在 checkout 时把 \n 换成 \r\n，制造无意义的整文件 diff
                        config.setString("core", null, "autocrlf", "false");
                        config.save();
                    }
                    baseline(agent, kind);
                    log.info("RSI 仓库已创建 agent={} kind={} gitDir={}", agent, kind, gitDir);
                } catch (IOException e) {
                    throw new UncheckedIOException("创建 RSI 仓库失败：" + gitDir, e);
                }
            }
            return null;
        });
    }

    /**
     * 提交当前 store 内的全部改动（新增、修改、删除），一次沉淀一次提交。
     *
     * @param summary  提交说明首行，形如 {@code memory(l1): +2 -1 merge 1（session s-abc123）}
     * @param trailers 结构化 trailer（Rsi-Kind / Rsi-Agent / Rsi-Source-Session / Rsi-Trace-Id / Rsi-Layer）
     * @return 提交 id；没有实际内容变化时返回 null
     */
    public String commit(String agent, String kind, String summary, Map<String, String> trailers) {
        ensure(agent, kind);
        return locked(agent, kind, () -> {
            try (Git git = newGit(agent, kind)) {
                Set<String> dirty = dirtyPaths(git);
                if (dirty.isEmpty()) {
                    // 内容与上次一致（例如 L1 重写后没有任何行变化），不留空提交
                    return null;
                }
                stage(git, dirty);
                try {
                    Map<String, String> all = new LinkedHashMap<>();
                    if (trailers != null) {
                        all.putAll(trailers);
                    }
                    RevCommit commit = git.commit()
                            .setMessage(message(summary, all))
                            .setAuthor(identity(agent))
                            .setCommitter(identity(agent))
                            .call();
                    log.info("RSI 沉淀提交 agent={} kind={} commit={} summary={}",
                            agent, kind, commit.getName(), summary);
                    return commit.getName();
                } catch (EmptyCommitException e) {
                    return null;
                }
            } catch (GitAPIException e) {
                throw new IllegalStateException("RSI 提交失败 agent=" + agent + " kind=" + kind, e);
            }
        });
    }

    /**
     * 全部提交，新的在前。仓库尚不存在时返回空列表（agent 还没有沉淀过）。
     */
    public List<RsiCommit> log(String agent, String kind) {
        paths.kind(kind);
        if (!Files.isRegularFile(paths.gitDir(agent, kind).resolve(Constants.HEAD))) {
            return List.of();
        }
        return locked(agent, kind, () -> {
            try (Git git = newGit(agent, kind); RevWalk walk = new RevWalk(git.getRepository())) {
                ObjectId head = git.getRepository().resolve(Constants.HEAD);
                if (head == null) {
                    return List.of();
                }
                walk.markStart(walk.parseCommit(head));
                List<RsiCommit> commits = new ArrayList<>();
                for (RevCommit commit : walk) {
                    commits.add(toRsiCommit(commit));
                }
                return commits;
            } catch (IOException e) {
                throw new UncheckedIOException("读取 RSI 提交历史失败 agent=" + agent, e);
            }
        });
    }

    /**
     * 该提交与其父提交的 unified diff（供 diff2html 渲染）；基线提交（无父）与空树比较。
     */
    public String diff(String agent, String kind, String commitId) {
        return locked(agent, kind, () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Git git = newGit(agent, kind);
                 RevWalk walk = new RevWalk(git.getRepository());
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(git.getRepository());
                formatter.setDetectRenames(false);
                RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
                List<DiffEntry> entries;
                if (commit.getParentCount() == 0) {
                    entries = formatter.scan(new EmptyTreeIterator(), treeIterator(walk, commit.getTree()));
                } else {
                    RevCommit parent = walk.parseCommit(commit.getParent(0));
                    entries = formatter.scan(treeIterator(walk, parent.getTree()),
                            treeIterator(walk, commit.getTree()));
                }
                if (entries.isEmpty()) {
                    return "";
                }
                formatter.format(entries);
                formatter.flush();
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("生成提交 diff 失败：" + commitId, e);
            }
        });
    }

    /**
     * 任意两个提交之间的 unified diff，from 为旧、to 为新。
     */
    public String diff(String agent, String kind, String from, String to) {
        return locked(agent, kind, () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (Git git = newGit(agent, kind);
                 RevWalk walk = new RevWalk(git.getRepository());
                 DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(git.getRepository());
                formatter.setDetectRenames(false);
                RevCommit oldCommit = walk.parseCommit(ObjectId.fromString(from));
                RevCommit newCommit = walk.parseCommit(ObjectId.fromString(to));
                List<DiffEntry> entries = formatter.scan(treeIterator(walk, oldCommit.getTree()),
                        treeIterator(walk, newCommit.getTree()));
                if (entries.isEmpty()) {
                    return "";
                }
                formatter.format(entries);
                formatter.flush();
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("生成区间 diff 失败：" + from + ".." + to, e);
            }
        });
    }

    /**
     * 回溯：直接 {@code reset --hard} 到指定提交，就这一个动作。
     *
     * <p>无审批、无 backup ref、无额外保全——store 无人类修改，回溯即恢复；
     * 回溯点之后的提交变为不可达（语义上等于删除，git 对象仍在，交给 gc）。
     * 回溯后下一次沉淀将从回溯点线性继续提交。</p>
     *
     * <p>注意：reset --hard 会把 HEAD 之后的<b>已跟踪</b>文件恢复/删除到位；
     * worktree 内不应存在未跟踪文件（store 只有沉淀一个写者），无需额外清理。</p>
     *
     * @return 回溯后的 HEAD 提交 id
     */
    public String resetTo(String agent, String kind, String commitId) {
        ensure(agent, kind);
        return locked(agent, kind, () -> {
            try (Git git = newGit(agent, kind)) {
                git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef(commitId)
                        .call();
                String head = headId(git);
                log.info("RSI 回溯完成 agent={} kind={} -> {} HEAD={}", agent, kind, commitId, head);
                return head;
            } catch (GitAPIException e) {
                throw new IllegalStateException("RSI 回溯失败 agent=" + agent + " commit=" + commitId, e);
            }
        });
    }

    /**
     * 指定版本（缺省 HEAD）下的单文件内容。
     *
     * @return 文件文本；该版本不存在此文件时返回 null
     */
    public String readFile(String agent, String kind, String relativePath, String commitId) {
        return locked(agent, kind, () -> {
            try (Git git = newGit(agent, kind); RevWalk walk = new RevWalk(git.getRepository())) {
                RevCommit commit = resolveCommit(git, walk, commitId);
                byte[] bytes = contentIn(git.getRepository(), commit.getTree(), relativePath);
                return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("读取历史文件失败：" + relativePath, e);
            }
        });
    }

    /**
     * 指定版本（缺省 HEAD）下的文件树（递归展开的全部文件相对路径），按名称排序。
     */
    public List<String> files(String agent, String kind, String commitId) {
        if (!Files.isRegularFile(paths.gitDir(agent, kind).resolve(Constants.HEAD))) {
            return List.of();
        }
        return locked(agent, kind, () -> {
            List<String> result = new ArrayList<>();
            try (Git git = newGit(agent, kind); RevWalk walk = new RevWalk(git.getRepository());
                 TreeWalk treeWalk = new TreeWalk(git.getRepository())) {
                RevCommit commit = resolveCommit(git, walk, commitId);
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    result.add(treeWalk.getPathString());
                }
                return result;
            } catch (IOException e) {
                throw new UncheckedIOException("读取版本文件树失败：" + commitId, e);
            }
        });
    }

    /**
     * 当前 HEAD 提交 id；仓库尚不存在时返回 null。
     */
    public String head(String agent, String kind) {
        if (!Files.isRegularFile(paths.gitDir(agent, kind).resolve(Constants.HEAD))) {
            return null;
        }
        return locked(agent, kind, () -> {
            try (Git git = newGit(agent, kind)) {
                return headId(git);
            }
        });
    }

    /**
     * 失效相关内存缓存的钩子，P1 接入技能清单/记忆缓存后由调用方扩展；当前为空实现。
     */
    public void afterReset(String agent) {
        // reset 后技能清单缓存、召回缓存的失效点；当前两个缓存尚未引入，保留扩展位
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具方法                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 基线提交：空树 + allow-empty，代表「沉淀开始之前」。
     */
    private void baseline(String agent, String kind) {
        try (Git git = newGit(agent, kind)) {
            git.commit()
                    .setMessage(message("baseline", Map.of(TRAILER_KIND, kind, TRAILER_AGENT, agent)))
                    .setAuthor(identity(agent))
                    .setCommitter(identity(agent))
                    .setAllowEmpty(true)
                    .call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("创建 RSI 基线提交失败 agent=" + agent, e);
        }
    }

    /**
     * 当前工作区里所有「与上次提交不一致」的路径（含未跟踪、已删除）。
     */
    private Set<String> dirtyPaths(Git git) {
        try {
            var status = git.status().call();
            Set<String> dirty = new TreeSet<>();
            dirty.addAll(status.getAdded());
            dirty.addAll(status.getChanged());
            dirty.addAll(status.getModified());
            dirty.addAll(status.getRemoved());
            dirty.addAll(status.getMissing());
            dirty.addAll(status.getUntracked());
            return dirty;
        } catch (GitAPIException e) {
            throw new IllegalStateException("读取 RSI store 状态失败", e);
        }
    }

    /**
     * 暂存指定路径：存在的文件用 add，已删除的文件用 add --update。
     */
    private void stage(Git git, Set<String> paths) throws GitAPIException {
        for (String path : paths) {
            if (Files.exists(workTreeOf(git).resolve(path))) {
                git.add().addFilepattern(path).call();
            } else {
                git.add().setUpdate(true).addFilepattern(path).call();
            }
        }
    }

    private Path workTreeOf(Git git) {
        return git.getRepository().getWorkTree().toPath();
    }

    private RevCommit resolveCommit(Git git, RevWalk walk, String commitId) throws IOException {
        ObjectId id = commitId == null || commitId.isBlank()
                ? git.getRepository().resolve(Constants.HEAD)
                : ObjectId.fromString(commitId);
        if (id == null) {
            throw new IllegalArgumentException("仓库不存在提交：" + commitId);
        }
        return walk.parseCommit(id);
    }

    private String headId(Git git) {
        try {
            ObjectId head = git.getRepository().resolve(Constants.HEAD);
            return head == null ? null : head.getName();
        } catch (IOException e) {
            throw new UncheckedIOException("读取 HEAD 失败", e);
        }
    }

    /**
     * 把提交树包装成 DiffFormatter.scan 需要的树迭代器。
     */
    private CanonicalTreeParser treeIterator(RevWalk walk, RevTree tree) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(walk.getObjectReader(), tree);
        return parser;
    }

    private static byte[] contentIn(Repository repository, RevTree tree, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repository, path, tree)) {
            if (walk == null) {
                return null;
            }
            ObjectLoader loader = repository.open(walk.getObjectId(0));
            return loader.getBytes();
        }
    }

    private RsiCommit toRsiCommit(RevCommit commit) {
        Map<String, String> trailers = trailers(commit.getFullMessage());
        String summary = commit.getFullMessage().split("\n", 2)[0].trim();
        return new RsiCommit(
                commit.getName(),
                commit.getParentCount() == 0 ? null : commit.getParent(0).getName(),
                Instant.ofEpochSecond(commit.getCommitTime()),
                summary,
                trailers.get(TRAILER_SOURCE_SESSION),
                trailers.get(TRAILER_TRACE_ID),
                trailers.get(TRAILER_LAYER));
    }

    /**
     * 解析 commit message 中的 Rsi-* trailer 为 map。
     */
    static Map<String, String> trailers(String messageText) {
        Map<String, String> result = new LinkedHashMap<>();
        if (messageText == null) {
            return result;
        }
        for (String line : messageText.split("\n")) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String key = line.substring(0, separator).trim();
            if (key.startsWith("Rsi-")) {
                result.put(key, line.substring(separator + 1).trim());
            }
        }
        return result;
    }

    /**
     * 提交说明 = 首行摘要 + 空行 + trailer 块。
     */
    static String message(String summary, Map<String, String> trailers) {
        StringBuilder builder = new StringBuilder(summary == null ? "" : summary);
        if (trailers != null && !trailers.isEmpty()) {
            builder.append("\n\n");
            trailers.forEach((key, value) -> builder.append(key).append(": ").append(value).append('\n'));
        }
        return builder.toString();
    }

    private static PersonIdent identity(String agent) {
        // 作者邮箱按 agent 区分，方便在 git log 里一眼看出是哪个 agent 的沉淀
        return new PersonIdent(AUTHOR, agent.toLowerCase() + "." + AUTHOR_EMAIL);
    }

    /**
     * 打开仓库对象：git-dir 与 work-tree 分离，这是「git 元数据不进 store 目录」的关键。
     */
    private Repository openRepository(Path gitDir, Path workTree) {
        try {
            return new FileRepositoryBuilder()
                    .setGitDir(gitDir.toFile())
                    .setWorkTree(workTree.toFile())
                    .setMustExist(false)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException("打开 RSI 仓库失败：" + gitDir, e);
        }
    }

    private Git newGit(String agent, String kind) {
        return new Git(openRepository(paths.gitDir(agent, kind), paths.kindDir(agent, kind)));
    }

    /**
     * 统一加锁入口：同一 (agent, kind) 仓库的读写串行化，防止 index 被并发改写。
     */
    private <T> T locked(String agent, String kind, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(agent + ":" + kind, key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
