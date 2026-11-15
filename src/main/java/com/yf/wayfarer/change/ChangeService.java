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
 * <p>数据源是各工作区的影子仓库，本身不存任何状态。整体链路：</p>
 * <pre>
 * 记录：ToolCallHandler → ChangeRecorder → ShadowRepository.commit
 * 查询：页面 → ChangeController → list / diff → 遍历仓库、解析 trailer、过滤会话与轮次
 * 撤销：页面 → ChangeController → revert → ShadowRepository.revert（恢复父提交内容 + 回退提交）
 * reset：SessionController → revertSessionChanges（按保留的 trace 集合算边界，再批量回退）
 * </pre>
 *
 * <p>「按会话」「按轮次」都只是在内存里过滤提交的 trailer，因此查询成本与仓库提交数成正比；
 * 这也意味着会话 fork/clone 之后，两个会话共享同一条文件时间线，各自只能看到
 * {@code Wayfarer-Session} 等于自己的那些提交。</p>
 *
 * @author yyf
 * @description 以影子仓库为数据源，提供变更列表、单提交 diff、撤销与 reset 回退
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
                // 基线提交是工作区的原始快照，不对应任何一次运行
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
            List<Map<String, Object>> files = filesOf(located.repository(), located.commit().commitId());
            // 没有文件差异的提交（例如重复撤销同一路径后内容已与父提交相同）不占列表位置
            if (files.isEmpty()) {
                continue;
            }
            result.add(view(located, files));
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
     * 判断某个提交里某个文件是否为二进制；二进制文件无法生成文本补丁。
     */
    private static boolean isBinary(ShadowRepository repository, String commitId, String path) {
        for (ChangeFile file : repository.files(commitId)) {
            if (file.path().equals(path)) {
                return file.binary();
            }
        }
        return false;
    }

    /**
     * 某个文件在一轮（或整个会话）里的净变化。
     *
     * <p>同一轮里同一个文件可能被多次工具调用改过，页面按文件展示时需要一段完整补丁：
     * 取该文件最早那次改动的父提交作为基准、最晚那次改动作为目标，两者对比即净变化。
     * 这样就不必把每次调用的碎片补丁拼在一起，也不会因为中间提交互相覆盖而显示错乱。</p>
     *
     * @param traceId 为空时按整个会话统计
     */
    public Map<String, Object> fileDiff(String sessionId, String traceId, String path) {
        if (blank(path)) {
            throw new IllegalArgumentException("缺少参数：path");
        }
        List<Located> matched = new ArrayList<>();
        for (ShadowRepository repository : store.all()) {
            for (ChangeCommit commit : repository.commits()) {
                if (ShadowRepository.BASELINE_TOOL.equals(commit.tool())) {
                    continue;
                }
                if (!blank(sessionId) && !sessionId.equals(commit.sessionId())) {
                    continue;
                }
                if (!blank(traceId) && !traceId.equals(commit.traceId())) {
                    continue;
                }
                if (!repository.changedPaths(commit.commitId()).contains(path)) {
                    continue;
                }
                matched.add(new Located(repository, commit));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        if (matched.isEmpty()) {
            result.put("binary", false);
            result.put("patch", "");
            return result;
        }
        // commits() 是新的在前，所以第一项是最晚一次改动、最后一项是最早一次改动；
        // 不按时间戳排序：同一秒内的多次提交时间戳相同，排序结果不稳定
        Located latest = matched.get(0);
        Located earliest = matched.get(matched.size() - 1);
        boolean binary = isBinary(latest.repository(), latest.commit().commitId(), path);
        result.put("binary", binary);
        result.put("patch", binary ? "" : earliest.repository()
                .diffRange(earliest.commit().parentId(), latest.commit().commitId(), path));
        return result;
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
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reverted", reverted);
        result.put("revertCommits", revertCommits);
        result.put("files", files);
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

    private Map<String, Object> view(Located located, List<Map<String, Object>> files) {
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
        entry.put("files", files);
        return entry;
    }

    private static List<Map<String, Object>> filesOf(ShadowRepository repository, String commitId) {
        List<Map<String, Object>> files = new ArrayList<>();
        for (ChangeFile file : repository.files(commitId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", file.path());
            item.put("changeType", file.changeType());
            item.put("insertions", file.insertions());
            item.put("deletions", file.deletions());
            item.put("binary", file.binary());
            files.add(item);
        }
        return files;
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
