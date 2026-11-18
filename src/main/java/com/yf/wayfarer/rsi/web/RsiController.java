package com.yf.wayfarer.rsi.web;

import com.yf.wayfarer.agent.AgentCatalog;
import com.yf.wayfarer.rsi.RsiGitService;
import com.yf.wayfarer.rsi.RsiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RSI 沉淀仓库的查询与回溯接口。
 *
 * <p>风格对齐 change 包的 ChangeController：只读查询为主，外加一个回溯动作。
 * kind ∈ {skill, memory}，agent 必须是已注册蓝图（白名单校验），
 * 文件路径参数经 RsiPaths 防穿越解析。</p>
 *
 * @author yyf
 * @description Web 控制台后端：agent 列表、变更历史、diff（单提交 / 任意两点）、
 * 版本文件树、单文件内容、reset --hard 回溯
 */
@RestController
@RequestMapping("/api/rsi")
public class RsiController {

    private static final Logger log = LoggerFactory.getLogger(RsiController.class);

    private final RsiGitService git;
    private final RsiPaths paths;
    private final AgentCatalog catalog;

    public RsiController(RsiGitService git, RsiPaths paths, AgentCatalog catalog) {
        this.git = git;
        this.paths = paths;
        this.catalog = catalog;
    }

    /**
     * 已注册 agent 名称列表（前端当前先静态写死同一列表，接口留作后续动态化）。
     */
    @GetMapping("/agents")
    public List<String> agents() {
        return new ArrayList<>(catalog.names());
    }

    /**
     * 变更历史，新的在前；每条含 hash、时间、摘要与 trailer（来源会话 / trace / 层）。
     */
    @GetMapping("/{kind}/commits")
    public List<RsiCommitView> commits(@PathVariable("kind") String kind,
                                       @RequestParam("agent") String agent) {
        paths.kind(kind);
        paths.agent(agent);
        return git.log(agent, kind).stream()
                .map(RsiCommitView::from)
                .toList();
    }

    /**
     * diff：给定 commit 时与其父比较；给定 from+to 时任意两点比较。
     */
    @GetMapping("/{kind}/diff")
    public Map<String, Object> diff(@PathVariable("kind") String kind,
                                    @RequestParam("agent") String agent,
                                    @RequestParam(name = "commit", required = false) String commit,
                                    @RequestParam(name = "from", required = false) String from,
                                    @RequestParam(name = "to", required = false) String to) {
        paths.kind(kind);
        paths.agent(agent);
        String text = commit != null && !commit.isBlank()
                ? git.diff(agent, kind, commit)
                : git.diff(agent, kind, require(from, "from"), require(to, "to"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diff", text);
        return result;
    }

    /**
     * 指定版本（缺省 HEAD）的文件树。
     */
    @GetMapping("/{kind}/files")
    public Map<String, Object> files(@PathVariable("kind") String kind,
                                     @RequestParam("agent") String agent,
                                     @RequestParam(name = "commit", required = false) String commit) {
        paths.kind(kind);
        paths.agent(agent);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("head", git.head(agent, kind));
        result.put("files", git.files(agent, kind, commit));
        return result;
    }

    /**
     * 单文件内容：展示 skill / memory 详情用；commit 缺省读 HEAD。
     */
    @GetMapping("/{kind}/file")
    public Map<String, Object> file(@PathVariable("kind") String kind,
                                    @RequestParam("agent") String agent,
                                    @RequestParam("path") String path,
                                    @RequestParam(name = "commit", required = false) String commit) {
        paths.kind(kind);
        paths.agent(agent);
        // 防穿越：先经 RsiPaths 解析校验（非法即抛异常），仓库里用仓库内的相对路径形式
        paths.resolveInStore(agent, kind, path);
        String content = git.readFile(agent, kind, path.replace('\\', '/'), commit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", path);
        result.put("content", content);
        return result;
    }

    /**
     * 回溯：直接 reset --hard（纯 git 移动指针，无审批无保全）。
     * 操作记入应用日志（含来源 IP 与时间由 access log 承担，这里记录业务信息）。
     */
    @PostMapping("/{kind}/reset")
    public Map<String, Object> reset(@PathVariable("kind") String kind,
                                     @RequestParam("agent") String agent,
                                     @RequestParam("commit") String commit) {
        paths.kind(kind);
        paths.agent(agent);
        String head = git.resetTo(agent, kind, commit);
        git.afterReset(agent);
        log.warn("RSI 回溯 agent={} kind={} commit={} 新HEAD={}", agent, kind, commit, head);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("head", head);
        return result;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少参数：" + name);
        }
        return value;
    }

    /**
     * @author yyf
     * @description 变更历史列表项：驼峰字段直接给前端，避免前端再做下划线转换
     */
    public record RsiCommitView(String hash, String parentHash, String time, String summary,
                                String sourceSession, String traceId, String layer) {

        static RsiCommitView from(com.yf.wayfarer.rsi.model.RsiCommit commit) {
            return new RsiCommitView(commit.hash(), commit.parentHash(),
                    commit.time() == null ? null : commit.time().toString(),
                    commit.summary(), commit.sourceSession(), commit.traceId(), commit.layer());
        }
    }
}
