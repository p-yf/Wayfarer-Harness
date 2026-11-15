package com.yf.wayfarer.change;

import com.yf.wayfarer.session.SessionMessage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 变更记录的查询与回退编排。
 *
 * @author yyf
 * @description 以工作区的影子仓库为数据源，按会话/轮次过滤提交，提供 diff 与撤销；
 * 会话 reset 时按「保留的 trace 集合」找出需要回退的提交，见 docs/shadow-git-spec.md 6.5
 */
public final class ChangeService {

    private final ShadowRepositoryStore store;

    public ChangeService(ShadowRepositoryStore store) {
        this.store = store;
    }

    /**
     * 查询变更条目，新的在前。
     *
     * @param sessionId 为空时返回所有会话的提交
     * @param traceId   为空时不按轮次过滤
     */
    public List<Map<String, Object>> list(String sessionId, String traceId, int limit) {
        List<Located> matched = new ArrayList<>();
        for (ShadowRepository repository : store.all()) {
            for (ChangeCommit commit : repository.commits()) {
                // 基线提交是工作区的原始快照，不对应任何工具调用
                if (ShadowRepository.BASELINE_TOOL.equals(commit.tool())) {
                    continue;
                }
                if (!blank(sessionId) && !sessionId.equals(commit.sessionId())) {
                    continue;
                }
                if (!blank(traceId) && !traceId.equals(commit.traceId())) {
                    continue;
                }
                matched.add(new Located(repository, commit));
            }
        }
        matched.sort(Comparator.comparing((Located located) -> located.commit().createdAt()).reversed());

        List<Map<String, Object>> result = new ArrayList<>();
        int max = limit <= 0 ? 50 : limit;
        for (Located located : matched) {
            if (result.size() >= max) {
                break;
            }
            result.add(view(located));
        }
        return result;
    }

    /**
     * 删除工作区对应的影子仓库，仅清理变更历史，工作区目录本身不动。
     */
    public boolean deleteWorkspace(String workspace) {
        return store.deleteWorkspace(workspace);
    }

    /**
     * 某个提交的 diff；path 为空时返回全部文件。
     */
    public Map<String, Object> diff(String commitId, String path) {
        ShadowRepository repository = repositoryOf(commitId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitId", commitId);
        result.put("path", path);
        result.put("patch", repository.diff(commitId, path));
        return result;
    }

    /**
     * 撤销一个提交（可只撤销其中一个路径）。
     *
     * @param path 为空表示撤销整次调用
     */
    public Map<String, Object> revert(String commitId, String path) {
        ShadowRepository repository = repositoryOf(commitId);
        ChangeCommit commit = commitOf(repository, commitId);
        if (ShadowRepository.BASELINE_TOOL.equals(commit.tool())) {
            throw new IllegalArgumentException("基线提交不可撤销");
        }
        if (!blank(path) && !repository.changedPaths(commitId).contains(path)) {
            throw new IllegalArgumentException("该提交不包含路径：" + path);
        }

        Map<String, String> trailers = new LinkedHashMap<>();
        trailers.put(ShadowRepository.TRAILER_SESSION, nullSafe(commit.sessionId()));
        trailers.put(ShadowRepository.TRAILER_TRACE, nullSafe(commit.traceId()));
        trailers.put(ShadowRepository.TRAILER_TOOL, "revert");
        trailers.put(ShadowRepository.TRAILER_REASON, "manual-revert");
        trailers.put(ShadowRepository.TRAILER_REVERTS, commitId);

        String summary = blank(path) ? "revert " + commit.summary() : "revert " + path;
        RevertOutcome outcome = repository.revert(List.of(commitId), blank(path) ? null : path, summary, trailers);
        return outcomeView(outcome);
    }

    /**
     * 会话 reset 后的文件回退：只回退本会话在边界之后的提交，不动其它会话的记录。
     *
     * @param survivingTraces reset 目标之前（保留）的全部 trace id，来自会话文件顺序
     * @param resetTraceId    reset 的目标轮次，写进回退提交便于页面归属
     */
    public Map<String, Object> revertSessionChanges(String sessionId, Set<String> survivingTraces,
                                                    String resetTraceId) {
        int reverted = 0;
        List<String> revertCommits = new ArrayList<>();
        List<String> files = new ArrayList<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();

        for (ShadowRepository repository : store.all()) {
            // commits() 是新的在前，转成从旧到新，边界才好按「提交链顺序」算
            List<ChangeCommit> commits = new ArrayList<>(repository.commits());
            commits.removeIf(commit -> ShadowRepository.BASELINE_TOOL.equals(commit.tool()));
            if (commits.isEmpty()) {
                continue;
            }
            java.util.Collections.reverse(commits);

            List<ChangeCommit> mine = commits.stream()
                    .filter(commit -> sessionId.equals(commit.sessionId()))
                    .toList();
            if (mine.isEmpty()) {
                continue;
            }

            // 基准 = 本会话中 trace 仍在保留集合里的最后一条提交；一条都没有则从本会话第一提交之前开始回退
            int boundary = -1;
            for (int i = 0; i < mine.size(); i++) {
                String trace = mine.get(i).traceId();
                if (trace != null && survivingTraces.contains(trace)) {
                    boundary = i;
                }
            }
            List<ChangeCommit> target = mine.subList(boundary + 1, mine.size());
            if (target.isEmpty()) {
                continue;
            }

            // 回退必须逆序（新 → 旧），同一文件被多次改动时才能收敛到最早那次改动之前的状态
            List<String> ids = new ArrayList<>(target.stream().map(ChangeCommit::commitId).toList());
            java.util.Collections.reverse(ids);

            Map<String, String> trailers = new LinkedHashMap<>();
            trailers.put(ShadowRepository.TRAILER_SESSION, sessionId);
            trailers.put(ShadowRepository.TRAILER_TRACE, nullSafe(resetTraceId));
            trailers.put(ShadowRepository.TRAILER_TOOL, "revert");
            trailers.put(ShadowRepository.TRAILER_REASON, "session-reset");
            trailers.put(ShadowRepository.TRAILER_REVERTS, String.join(",", ids));

            RevertOutcome outcome = repository.revert(ids, null, "revert session changes", trailers);
            if (outcome.commitId() != null) {
                reverted++;
                revertCommits.add(outcome.commitId());
            }
            files.addAll(outcome.files());
            conflicts.addAll(outcome.conflicts());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reverted", reverted);
        result.put("revertCommits", revertCommits);
        result.put("files", files);
        result.put("conflicts", conflicts);
        return result;
    }

    /**
     * 计算 reset 之后仍然保留的 trace 集合：reset 目标之前的轮次。
     * 必须在删除会话消息之前调用，否则边界信息已经丢失。
     */
    public static Set<String> survivingTraces(List<SessionMessage> messages, String resetTraceId) {
        Set<String> traces = new LinkedHashSet<>();
        boolean found = false;
        for (SessionMessage message : messages) {
            String trace = message.traceId();
            if (trace == null || trace.isBlank()) {
                continue;
            }
            if (trace.equals(resetTraceId)) {
                found = true;
                break;
            }
            traces.add(trace);
        }
        if (!found) {
            throw new IllegalArgumentException("会话中不存在 traceId：" + resetTraceId);
        }
        return traces;
    }

    private Map<String, Object> view(Located located) {
        ChangeCommit commit = located.commit();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("commitId", commit.commitId());
        entry.put("parentId", commit.parentId());
        entry.put("sessionId", commit.sessionId());
        entry.put("traceId", commit.traceId());
        entry.put("tool", commit.tool());
        entry.put("toolCallId", commit.toolCallId());
        entry.put("summary", commit.summary());
        entry.put("createdAt", commit.createdAt().toString());

        List<Map<String, Object>> files = new ArrayList<>();
        for (ChangeFile file : located.repository().files(commit.commitId())) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path());
            item.put("changeType", file.changeType());
            item.put("insertions", file.insertions());
            item.put("deletions", file.deletions());
            item.put("binary", file.binary());
            files.add(item);
        }
        entry.put("files", files);
        return entry;
    }

    private Map<String, Object> outcomeView(RevertOutcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("commitId", outcome.commitId());
        result.put("files", outcome.files());
        result.put("conflicts", outcome.conflicts());
        return result;
    }

    private ShadowRepository repositoryOf(String commitId) {
        if (blank(commitId)) {
            throw new IllegalArgumentException("缺少参数：commitId");
        }
        ShadowRepository repository = store.forCommit(commitId);
        if (repository == null) {
            throw new IllegalArgumentException("变更记录不存在：" + commitId);
        }
        return repository;
    }

    private static ChangeCommit commitOf(ShadowRepository repository, String commitId) {
        for (ChangeCommit commit : repository.commits()) {
            if (commit.commitId().equals(commitId)) {
                return commit;
            }
        }
        throw new IllegalArgumentException("变更记录不存在：" + commitId);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * @author yyf
     * @description 提交与它所属仓库的配对，查询时需要仓库来算 diff
     */
    private record Located(ShadowRepository repository, ChangeCommit commit) {
    }
}
