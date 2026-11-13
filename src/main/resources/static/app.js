(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const chatEl = $("chat");
  const chatInner = $("chatInner");
  const inputEl = $("input");
  const emptyStateEl = $("emptyState");
  const esc = window.Markdown.escapeHtml;
  const AGENT_NAME = "Wayfarer";

  function loadTitles() {
    try {
      return JSON.parse(localStorage.getItem("wayfarer-titles") || "{}");
    } catch (_) {
      return {};
    }
  }

  const state = {
    sessions: [],
    currentId: null,
    blocks: [],
    running: false,
    abort: null,
    hitlJson: "[]",
    toolArgs: {},
    titles: loadTitles(),
    tab: "model",
    modelConfigs: {},
    compactConfigs: {},
    prompts: [],
    promptEditing: null,
    editing: null,
    workspace: "",
    pendingWorkspace: "",
    wsDir: null,
    metadata: {}
  };

  function toast(message, isError) {
    const el = document.createElement("div");
    el.className = "toast" + (isError ? " error" : "");
    el.textContent = message;
    $("toastWrap").appendChild(el);
    setTimeout(() => el.remove(), 3600);
  }

  // 自动跟随：只有视图停在底部时，流式新增内容才继续贴底；
  // 用户一旦向上滚开就交出滚动控制权，不再每帧抢回去
  const FOLLOW_SLACK = 80;
  let autoFollow = true;

  function scrollChat(force) {
    if (force) autoFollow = true;
    if (autoFollow) chatEl.scrollTop = chatEl.scrollHeight;
  }

  chatEl.addEventListener("scroll", () => {
    autoFollow = chatEl.scrollHeight - chatEl.scrollTop - chatEl.clientHeight <= FOLLOW_SLACK;
  }, { passive: true });

  function applyTheme(theme) {
    if (theme === "dark") document.body.setAttribute("data-ds-dark-theme", "");
    else document.body.removeAttribute("data-ds-dark-theme");
    localStorage.setItem("wayfarer-theme", theme);
  }

  function fmtTime(iso) {
    if (!iso) return "";
    const date = new Date(iso);
    if (isNaN(date)) return "";
    const now = new Date();
    const sameDay = date.toDateString() === now.toDateString();
    const pad = (n) => String(n).padStart(2, "0");
    if (sameDay) return pad(date.getHours()) + ":" + pad(date.getMinutes());
    return (date.getMonth() + 1) + "月" + date.getDate() + "日";
  }

  function sessionTitle(meta) {
    return state.titles[meta.id] || meta.name || "未命名会话";
  }

  function rememberTitle(id, query) {
    if (!state.titles[id]) {
      state.titles[id] = query.length > 30 ? query.slice(0, 30) + "…" : query;
      localStorage.setItem("wayfarer-titles", JSON.stringify(state.titles));
    }
  }

  async function loadSessions() {
    try {
      state.sessions = await Api.listSessions();
      renderSessions();
    } catch (e) {
      toast(e.message, true);
    }
  }

  function renderSessions() {
    const list = $("sessionList");
    if (!state.sessions.length) {
      list.innerHTML = '<div class="session-empty">还没有会话，点击上方「新会话」开始</div>';
      return;
    }
    list.innerHTML = "";
    for (const meta of state.sessions) {
      const item = document.createElement("div");
      item.className = "session-item" + (meta.id === state.currentId ? " active" : "");
      item.innerHTML =
        '<div class="s-title">' + esc(sessionTitle(meta)) + '</div>' +
        '<div class="s-time">' + esc(fmtTime(meta.createdAt)) + '</div>' +
        '<button class="s-copy" title="复制会话"><svg viewBox="0 0 16 16" width="13" height="13"><rect x="5.2" y="5.2" width="7.6" height="7.6" rx="1.6" stroke="currentColor" stroke-width="1.2" fill="none"/><path d="M10.4 3.2H4.6a1.6 1.6 0 0 0-1.6 1.6v5.8" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linecap="round"/></svg></button>' +
        '<button class="s-del" title="删除会话"><svg viewBox="0 0 16 16" width="13" height="13"><path d="M3 4h10M6.5 4V2.8h3V4M4.5 4l.6 9h5.8l.6-9M6.8 6.5v4M9.2 6.5v4" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linecap="round"/></svg></button>';
      item.addEventListener("click", () => openSession(meta.id));
      item.querySelector(".s-copy").addEventListener("click", async (e) => {
        e.stopPropagation();
        if (state.running) {
          toast("正在运行中，请先停止当前任务", true);
          return;
        }
        try {
          const result = await Api.cloneSession(meta.id);
          await loadSessions();
          await openSession(result.sessionId);
          toast("已复制为新会话");
        } catch (err) {
          toast(err.message, true);
        }
      });
      item.querySelector(".s-del").addEventListener("click", async (e) => {
        e.stopPropagation();
        if (!confirm("删除会话「" + sessionTitle(meta) + "」？该操作不可恢复。")) return;
        try {
          await Api.deleteSession(meta.id);
          if (state.currentId === meta.id) {
            state.currentId = null;
            state.blocks = [];
            renderChat();
          }
          await loadSessions();
          toast("会话已删除");
        } catch (err) {
          toast(err.message, true);
        }
      });
      list.appendChild(item);
    }
  }

  async function createSession() {
    try {
      const meta = await Api.createSession("未命名会话");
      await savePendingWorkspace(meta.id);
      await loadSessions();
      await openSession(meta.id);
      inputEl.focus();
    } catch (e) {
      toast(e.message, true);
    }
  }

  async function openSession(id) {
    if (state.running) {
      toast("正在运行中，请先停止当前任务", true);
      return;
    }
    state.currentId = id;
    state.blocks = [];
    state.toolArgs = {};
    renderSessions();
    renderChat();
    try {
      const meta = await Api.sessionMeta(id);
      state.metadata = (meta && meta.metadata) || {};
      state.workspace = state.metadata.workspace || "";
      renderWorkspace();
      const messages = await Api.sessionMessages(id);
      state.blocks = historyToBlocks(messages);
      renderChat();
      scrollChat(true);
    } catch (e) {
      toast(e.message, true);
    }
    if (window.innerWidth <= 900) $("sidebar").classList.remove("open");
  }

  function renderWorkspace() {
    const label = $("workspaceName");
    label.textContent = state.workspace || "未选择工作区";
    $("workspaceBtn").title = state.workspace ? "工作区：" + state.workspace : "选择工作区";
    $("workspaceBtn").classList.toggle("set", !!state.workspace);
  }

  /**
   * 打开工作区选择器，从当前工作区或服务端默认目录开始浏览。
   * 浏览器不暴露本地绝对路径，因此目录列表由后端只读接口提供，
   * 选择动作与结果保存都在前端完成。
   */
  async function openWorkspacePicker() {
    $("wsMask").classList.remove("hidden");
    $("wsDialog").classList.remove("hidden");
    await loadWorkspaceDir(state.workspace || "");
  }

  function closeWorkspacePicker() {
    $("wsMask").classList.add("hidden");
    $("wsDialog").classList.add("hidden");
  }

  async function loadWorkspaceDir(path) {
    let result;
    try {
      result = await Api.listWorkspace(path);
    } catch (e) {
      toast(e.message, true);
      return;
    }
    if (result && result.error) {
      toast(result.error, true);
      return;
    }
    state.wsDir = result;
    renderWorkspacePicker();
  }

  function renderWorkspacePicker() {
    const dir = state.wsDir;
    if (!dir) return;
    $("wsCurrent").textContent = dir.path || "此电脑";
    $("wsUp").disabled = !dir.parent;
    $("wsConfirm").disabled = !dir.path;

    const entries = [];
    if (dir.parent) entries.push({ path: dir.parent, name: "..", up: true });
    for (const root of dir.roots || []) entries.push({ path: root, name: root });
    for (const child of dir.directories || []) entries.push({ path: child, name: folderName(child) });

    const list = $("wsList");
    list.innerHTML = "";
    if (!entries.length) {
      list.innerHTML = '<div class="ws-empty">没有子目录</div>';
      return;
    }
    for (const entry of entries) {
      const row = document.createElement("button");
      row.type = "button";
      row.className = "ws-row" + (entry.up ? " up" : "");
      row.innerHTML = wsFolderIcon + '<span class="ws-row-name"></span>';
      row.querySelector(".ws-row-name").textContent = entry.name;
      row.addEventListener("click", () => loadWorkspaceDir(entry.path));
      list.appendChild(row);
    }
  }

  function folderName(path) {
    const parts = String(path).split(/[\\/]/).filter(Boolean);
    return parts.length ? parts[parts.length - 1] : path;
  }

  async function confirmWorkspace() {
    const dir = state.wsDir;
    if (!dir || !dir.path) return;
    closeWorkspacePicker();
    const path = dir.path;
    state.workspace = path;
    renderWorkspace();
    if (!state.currentId) {
      state.pendingWorkspace = path;
      toast("已选择工作区，创建会话时写入");
      return;
    }
    try {
      state.metadata = Object.assign({}, state.metadata, { workspace: path });
      await Api.updateSessionMetadata(state.currentId, state.metadata);
      toast("工作区已保存");
    } catch (e) {
      toast(e.message, true);
    }
  }

  async function savePendingWorkspace(sessionId) {
    if (!state.pendingWorkspace) return;
    try {
      await Api.updateSessionMetadata(sessionId, { workspace: state.pendingWorkspace });
      state.pendingWorkspace = "";
    } catch (e) {
      toast(e.message, true);
    }
  }

  /**
   * 助手消息上的轮次操作：从此分支（fork）或回退本轮（reset）。
   */
  async function messageAction(action, block) {
    if (state.running) {
      toast("正在运行中，请先停止当前任务", true);
      return;
    }
    if (!state.currentId || !block.traceId) return;

    if (action === "fork") {
      try {
        const result = await Api.forkSession(state.currentId, block.traceId);
        await loadSessions();
        await openSession(result.sessionId);
        toast("已从该轮之前分支出新会话");
      } catch (e) {
        toast(e.message, true);
      }
      return;
    }

    const query = precedingUserText(block);
    if (!confirm("将删除这一轮及之后的全部消息，确定回退？")) return;
    try {
      await Api.resetSession(state.currentId, block.traceId);
      await openSession(state.currentId);
      // 把该轮的问题放回输入框，方便改完直接重发
      if (query) inputEl.value = query;
      inputEl.focus();
      toast("已回退到本轮之前");
    } catch (e) {
      toast(e.message, true);
    }
  }

  function precedingUserText(block) {
    for (let i = state.blocks.indexOf(block); i >= 0; i--) {
      if (state.blocks[i].kind === "user") return state.blocks[i].content;
    }
    return "";
  }

  function historyToBlocks(messages) {
    const blocks = [];
    const toolsById = {};
    const headTraces = new Set();
    for (const msg of messages || []) {
      const content = msg.content || {};
      if (msg.type === "USER") {
        blocks.push({ kind: "user", content: content.content || "", traceId: msg.traceId });
      } else if (msg.type === "ASSISTANT") {
        // 每个 trace 只插入一个头像块，位于用户请求之后、该轮全部输出之前
        if (!headTraces.has(msg.traceId) &&
            (content.reasoningContent || content.content || (content.toolCalls || []).length)) {
          headTraces.add(msg.traceId);
          blocks.push({ kind: "head", traceId: msg.traceId });
        }
        if (content.reasoningContent) {
          blocks.push({ kind: "reasoning", content: content.reasoningContent, open: false, traceId: msg.traceId });
        }
        if (content.content) {
          blocks.push({ kind: "text", content: content.content, traceId: msg.traceId });
        }
        for (const call of content.toolCalls || []) {
          const block = { kind: "tool", id: call.id, name: call.name, arguments: call.arguments, result: null, open: false, traceId: msg.traceId };
          toolsById[call.id] = block;
          blocks.push(block);
        }
      } else if (msg.type === "TOOL") {
        const block = toolsById[content.toolCallId];
        if (block) {
          block.result = content.content || "";
        } else {
          blocks.push({ kind: "tool", id: content.toolCallId, name: "工具结果", arguments: null, result: content.content || "", open: false });
        }
      } else if (msg.type === "CUSTOM") {
        blocks.push(...customToBlocks(content));
      } else if (msg.type === "COMPACT") {
        blocks.push({ kind: "compact" });
      } else if (msg.type === "EXTRADATA") {
        blocks.push({
          kind: "end",
          finishReason: content.finishReason,
          usage: content.usage,
          durationMs: content.durationMs,
          traceId: msg.traceId
        });
      }
    }
    return blocks;
  }

  /**
   * 同一 trace 只保留一个 Wayfarer 头像块与一组轮次操作。
   */
  function ensureHead(traceId) {
    if (state.blocks.some((block) => block.kind === "head" && block.traceId === traceId)) return;
    pushBlock({ kind: "head", traceId: traceId });
  }

  /**
   * 自定义状态里只有计划需要上屏；其余键是 agent 的内部状态，不展示。
   */
  function customToBlocks(content) {
    const source = content || {};
    const plan = source["store:plan"] || source.plan;
    return plan && typeof plan === "object" ? [{ kind: "plan", plan: plan }] : [];
  }

  function prettyArgs(raw) {
    if (!raw) return "";
    try {
      return JSON.stringify(JSON.parse(raw), null, 2);
    } catch (_) {
      return raw;
    }
  }

  function planSortedKeys(plan) {
    const keys = Object.keys(plan);
    const numeric = keys.every((k) => /^\d+$/.test(k));
    return keys.sort(numeric ? (a, b) => Number(a) - Number(b) : undefined);
  }

  const PLAN_TOOLS = { plan: true, update_status: true, plan_list: true };
  const PLAN_LINE = /^(\d+)\.\s*\[(done|doing|not_begin)\]\s*(.*)$/;

  function parsePlanText(text) {
    if (typeof text !== "string" || !text.trim().startsWith("当前计划：")) return null;
    const plan = {};
    for (const line of text.split("\n")) {
      const matched = PLAN_LINE.exec(line.trim());
      if (matched) plan[matched[1]] = { content: matched[3], status: matched[2] };
    }
    return Object.keys(plan).length ? plan : null;
  }

  function lastPlanBlock() {
    for (let i = state.blocks.length - 1; i >= 0; i--) {
      if (state.blocks[i].kind === "plan") return state.blocks[i];
    }
    return null;
  }

  function applyPlanResult(name, result) {
    if (!PLAN_TOOLS[name]) return;
    const text = typeof result === "string" ? result : "";
    let plan = parsePlanText(text);
    if (!plan && text.indexOf("计划已清除") >= 0) {
      const previous = lastPlanBlock();
      if (!previous) return;
      const finished = {};
      for (const key of Object.keys(previous.plan || {})) {
        finished[key] = { content: (previous.plan[key] || {}).content || "", status: "done" };
      }
      plan = finished;
    }
    if (!plan) return;
    const existing = lastPlanBlock();
    if (existing) {
      existing.plan = plan;
      swapBlock(existing);
    } else {
      pushBlock({ kind: "plan", plan: plan });
    }
    scrollChat();
  }

  const toolIcon = '<svg viewBox="0 0 16 16" width="14" height="14"><path d="M10.2 2.6a3.2 3.2 0 0 0-4.4 3.9L2.4 9.9a1.5 1.5 0 0 0 2.1 2.1l3.4-3.4a3.2 3.2 0 0 0 3.9-4.4l-2 2-1.6-1.6z" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round"/></svg>';
  const wsFolderIcon = '<svg viewBox="0 0 16 16" width="14" height="14"><path d="M1.8 4.2a1 1 0 0 1 1-1h3l1.3 1.4h5.1a1 1 0 0 1 1 1v6.2a1 1 0 0 1-1 1H2.8a1 1 0 0 1-1-1z" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linejoin="round"/></svg>';

  // 轮次操作图标：分支与回退
  const forkIcon = '<svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="5" cy="4" r="2"/><circle cx="5" cy="16" r="2"/><circle cx="15" cy="8" r="2"/><path d="M5 6v8"/><path d="M15 10a4 4 0 0 1-4 4H5"/></svg>';
  const resetIcon = '<svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M6.5 5.5 3 9l3.5 3.5"/><path d="M3 9h7.5a4.5 4.5 0 0 1 0 9H8"/></svg>';

  function renderBlock(block) {
    const el = document.createElement("div");
    if (block.kind === "user") {
      el.className = "msg msg-user";
      el.innerHTML = '<div class="bubble"></div>';
      el.querySelector(".bubble").textContent = block.content;
    } else if (block.kind === "head") {
      el.className = "msg msg-head-block";
      el.innerHTML =
        '<div class="msg-head"><span class="avatar">W</span>Wayfarer' +
        (block.traceId
          ? '<div class="msg-actions">' +
            '<button type="button" data-act="fork" title="从此分支：保留这一轮之前的消息，另开一个会话" aria-label="从此分支">' + forkIcon + "</button>" +
            '<button type="button" data-act="reset" title="回退本轮：删除这一轮及之后的全部消息" aria-label="回退本轮">' + resetIcon + "</button>" +
            "</div>"
          : "") +
        "</div>";
      for (const button of el.querySelectorAll(".msg-actions button")) {
        button.addEventListener("click", () => messageAction(button.dataset.act, block));
      }
    } else if (block.kind === "text") {
      el.className = "msg msg-assistant";
      el.innerHTML = '<div class="msg-body">' + window.Markdown.render(block.content) + "</div>";
    } else if (block.kind === "reasoning") {
      el.className = "reasoning" + (block.open ? " open" : "");
      el.innerHTML =
        '<button type="button" class="reasoning-toggle"><span class="arrow">▶</span><span class="thinking-dot"></span>思考过程</button>' +
        '<div class="reasoning-content"></div>';
      el.querySelector(".reasoning-content").textContent = block.content;
    } else if (block.kind === "tool") {
      el.className = "tool-card" + (block.open ? " open" : "");
      const status = block.result == null ? '<span class="tool-status running">执行中</span>' : '<span class="tool-status done">已完成</span>';
      let detail = "";
      if (block.arguments) {
        detail += '<div class="tool-section"><div class="tool-label">入参</div><pre>' + esc(prettyArgs(block.arguments)) + "</pre></div>";
      }
      if (block.result != null) {
        detail += '<div class="tool-section"><div class="tool-label">返回</div><pre>' + esc(block.result) + "</pre></div>";
      }
      el.innerHTML =
        '<div class="tool-head"><span class="tool-icon">' + toolIcon + '</span>' +
        '<span class="tool-name">' + esc(block.name || "tool") + "</span>" + status +
        '<span class="arrow">▶</span></div>' +
        '<div class="tool-detail">' + detail + "</div>";
    } else if (block.kind === "plan") {
      el.className = "plan-card";
      let rows = "";
      for (const key of planSortedKeys(block.plan)) {
        const item = block.plan[key] || {};
        const status = item.status || "not_begin";
        const label = { done: "已完成", doing: "进行中", not_begin: "未开始" }[status] || status;
        rows +=
          '<div class="plan-row ' + esc(status) + '"><span class="plan-idx">' + esc(key) + '</span>' +
          '<span class="plan-badge ' + esc(status) + '">' + esc(label) + '</span>' +
          '<span class="plan-text">' + esc(item.content || "") + "</span></div>";
      }
      el.innerHTML = '<div class="plan-head">执行计划</div><div class="plan-list">' + rows + "</div>";
    } else if (block.kind === "compact") {
      el.className = "compact-mark";
      el.textContent = "较早的上下文已被压缩";
    } else if (block.kind === "end") {
      el.className = "run-meta";
      const usage = block.usage || {};
      const parts = [];
      if (block.durationMs != null) {
        parts.push("耗时 " + (block.durationMs >= 1000
          ? (block.durationMs / 1000).toFixed(1) + "s"
          : block.durationMs + "ms"));
      }
      if (block.finishReason) parts.push("finish: " + block.finishReason);
      if (usage.totalTokens != null) {
        parts.push("tokens: " + usage.totalTokens +
          " (prompt " + (usage.promptTokens ?? "-") + " / completion " + (usage.completionTokens ?? "-") + ")");
      }
      if (usage.reasoningTokens != null) parts.push("思考 " + usage.reasoningTokens);
      // 缓存命中率 = 命中 token / 输入 token
      if (usage.promptCacheHitTokens != null) {
        const prompt = usage.promptTokens;
        const rate = prompt ? Math.round((usage.promptCacheHitTokens / prompt) * 100) : null;
        parts.push("缓存命中 " + usage.promptCacheHitTokens +
          (prompt ? "/" + prompt : "") + (rate != null ? " (" + rate + "%)" : ""));
      }
      el.textContent = parts.join(" · ");
    } else if (block.kind === "other") {
      if (typeof block.data === "string") {
        el.className = block.data.indexOf("出错") === 0 ? "notice error" : "notice";
        el.textContent = block.data;
      } else {
        el.className = "other-json";
        el.textContent = JSON.stringify(block.data, null, 2);
      }
    }
    block.el = el;
    return el;
  }

  function renderChat() {
    chatInner.innerHTML = "";
    if (!state.blocks.length) {
      chatInner.appendChild(emptyStateEl);
      emptyStateEl.classList.remove("hidden");
      return;
    }
    for (const block of state.blocks) {
      chatInner.appendChild(renderBlock(block));
    }
    scrollChat();
  }

  function pushBlock(block) {
    state.blocks.push(block);
    if (state.blocks.length === 1) chatInner.innerHTML = "";
    chatInner.appendChild(renderBlock(block));
    scrollChat();
    return block;
  }

  /**
   * 就地替换某个块对应的 DOM。renderBlock 会把 block.el 指向新节点，
   * 所以必须先留存旧节点，否则替换会作用在未插入文档的新节点上而静默失效。
   */
  function swapBlock(block) {
    const previous = block.el;
    const fresh = renderBlock(block);
    if (previous && previous.parentNode) previous.replaceWith(fresh);
    return fresh;
  }

  /**
   * 流式片段到达频率高于屏幕刷新率，按帧合并重绘：先累积文本，每帧最多渲染一次。
   * 逐片全量重绘会让主线程反复做 markdown 解析，表现为文字一大片一大片地蹦出来。
   */
  const dirtyBlocks = new Set();
  let renderFrame = 0;

  function scheduleRender(block) {
    dirtyBlocks.add(block);
    if (renderFrame) return;
    renderFrame = requestAnimationFrame(() => {
      renderFrame = 0;
      paintDirty();
    });
  }

  function paintDirty() {
    for (const block of dirtyBlocks) {
      if (!block.el) continue;
      if (block.kind === "text") {
        const body = block.el.querySelector(".msg-body");
        if (body) body.innerHTML = window.Markdown.render(block.content);
      } else if (block.kind === "reasoning") {
        const body = block.el.querySelector(".reasoning-content");
        if (body) body.textContent = block.content;
      }
    }
    dirtyBlocks.clear();
    scrollChat();
  }

  function handleEvent(name, data) {
    if (name === "delta") {
      let block = state.blocks[state.blocks.length - 1];
      if (!block || block.kind !== "text") {
        ensureHead(data.traceId);
        block = pushBlock({
          kind: "text",
          content: "",
          traceId: data.traceId
        });
        block.el.querySelector(".msg-body").classList.add("cursor-blink");
      }
      block.content += data.content || "";
      scheduleRender(block);
    } else if (name === "reasoning") {
      let block = state.blocks[state.blocks.length - 1];
      if (!block || block.kind !== "reasoning") {
        ensureHead(data.traceId);
        block = pushBlock({ kind: "reasoning", content: "", open: true, traceId: data.traceId });
      }
      block.content += data.content || "";
      scheduleRender(block);
    } else if (name === "tool_call") {
      const call = data.call || {};
      state.toolArgs[call.id] = call.arguments || "";
      ensureHead(data.traceId);
      pushBlock({ kind: "tool", id: call.id, name: call.name, arguments: call.arguments, result: null, open: true, traceId: data.traceId });
    } else if (name === "tool_result") {
      let block = null;
      for (let i = state.blocks.length - 1; i >= 0; i--) {
        const candidate = state.blocks[i];
        if (candidate.kind === "tool" && candidate.id === data.toolCallId) {
          block = candidate;
          break;
        }
      }
      if (block) {
        block.result = data.content || "";
        block.open = false;
        swapBlock(block);
      } else {
        pushBlock({ kind: "tool", id: data.toolCallId, name: "工具结果", arguments: null, result: data.content || "", open: false });
      }
      applyPlanResult(block ? block.name : "", data.content || "");
      scrollChat();
    } else if (name === "trace_data") {
      // 每轮模型调用的耗时与用量，后端已持久化为 EXTRADATA
      paintDirty();
      pushBlock({
        kind: "end",
        finishReason: data.finishReason,
        usage: data.usage,
        durationMs: data.durationMs,
        traceId: data.traceId
      });
    } else if (name === "message_end") {
      // 先把待渲染的增量刷出来，再收尾，避免最后一段文字丢失
      paintDirty();
      for (const block of state.blocks) {
        if (block.kind === "text" && block.el) {
          const body = block.el.querySelector(".msg-body");
          if (body) body.classList.remove("cursor-blink");
        }
        if (block.kind === "reasoning" && block.el) {
          block.open = false;
          block.el.classList.remove("open");
        }
      }
    } else if (name === "other") {
      if (data && typeof data === "object" && !Array.isArray(data)) {
        for (const block of customToBlocks(data)) pushBlock(block);
      } else {
        pushBlock({ kind: "other", data: data });
      }
    }
  }

  function setRunning(running) {
    state.running = running;
    $("sendBtn").classList.toggle("hidden", running);
    $("stopBtn").classList.toggle("hidden", !running);
    inputEl.disabled = running;
    $("composerHint").textContent = running ? "行者 正在工作…" : "";
    // 只有运行期间才可能有待确认项，空闲时不必轮询
    if (running) startHitlPolling();
    else stopHitlPolling();
  }

  async function send() {
    const query = inputEl.value.trim();
    if (!query || state.running) return;
    if (!state.currentId) {
      try {
        const meta = await Api.createSession("未命名会话");
        state.currentId = meta.id;
        await savePendingWorkspace(meta.id);
        state.metadata = Object.assign({}, state.metadata, { workspace: state.workspace });
        await loadSessions();
      } catch (e) {
        toast(e.message, true);
        return;
      }
    }
    const sessionId = state.currentId;
    rememberTitle(sessionId, query);
    renderSessions();
    inputEl.value = "";
    autoResize();
    pushBlock({ kind: "user", content: query });
    scrollChat(true);
    setRunning(true);
    state.abort = new AbortController();
    const payload = { sessionId: sessionId, userquery: query, agentName: AGENT_NAME };
    if (state.workspace) payload.workspace = state.workspace;
    try {
      await Api.streamAgent(payload, { onEvent: handleEvent }, state.abort.signal);
    } catch (e) {
      if (e.name === "AbortError") {
        pushBlock({ kind: "other", data: "已停止" });
      } else {
        const detail = e.message.indexOf("未配置模型") >= 0
          ? e.message + "（点右上角「设置」添加并启用一个模型配置）"
          : e.message;
        toast(detail, true);
        pushBlock({ kind: "other", data: "出错：" + detail });
      }
    } finally {
      chatInner.querySelectorAll(".cursor-blink").forEach((el) => el.classList.remove("cursor-blink"));
      setRunning(false);
      state.abort = null;
      inputEl.focus();
    }
  }

  async function stop() {
    if (!state.currentId) return;
    try {
      await Api.stopAgent(state.currentId);
    } catch (e) {
      toast(e.message, true);
    }
    if (state.abort) state.abort.abort();
    setRunning(false);
  }

  let hitlTimer = 0;

  function startHitlPolling() {
    if (hitlTimer) return;
    pollHitl();
    hitlTimer = setInterval(pollHitl, 2500);
  }

  function stopHitlPolling() {
    if (hitlTimer) {
      clearInterval(hitlTimer);
      hitlTimer = 0;
    }
    // 运行结束后不会有新的待确认项，清掉残留卡片
    state.hitlJson = "";
    renderHitl([]);
  }

  async function pollHitl() {
    try {
      const pending = await Api.hitlPending();
      const json = JSON.stringify(pending);
      if (json === state.hitlJson) return;
      state.hitlJson = json;
      renderHitl(pending || []);
    } catch (_) {}
  }

  function renderHitl(pending) {
    const area = $("hitlArea");
    area.innerHTML = "";
    for (const item of pending) {
      const card = document.createElement("div");
      card.className = "hitl-card";
      const args = item.arguments || state.toolArgs[item.toolCallId] || "";
      card.innerHTML =
        '<div class="hitl-title"><span class="warn"><svg viewBox="0 0 16 16" width="15" height="15"><path d="M8 1.8L15 14H1z" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round"/><path d="M8 6.2v3.4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/><circle cx="8" cy="11.8" r="0.8" fill="currentColor"/></svg></span>' +
        '工具 <b>' + esc(item.toolName || "") + "</b> 请求人工确认</div>" +
        (args ? '<div class="hitl-args">' + esc(prettyArgs(args)) + "</div>" : "") +
        '<div class="hitl-actions"><button class="btn btn-approve">批准</button><button class="btn btn-reject">拒绝</button></div>';
      card.querySelector(".btn-approve").addEventListener("click", () => decide(item.toolCallId, true));
      card.querySelector(".btn-reject").addEventListener("click", () => decide(item.toolCallId, false));
      area.appendChild(card);
    }
  }

  async function decide(toolCallId, approved) {
    try {
      await Api.hitlDecide(toolCallId, approved);
      toast(approved ? "已批准" : "已拒绝");
    } catch (e) {
      toast(e.message, true);
    }
    state.hitlJson = "";
    pollHitl();
  }

  function autoResize() {
    inputEl.style.height = "auto";
    inputEl.style.height = Math.min(inputEl.scrollHeight, 200) + "px";
  }

  function openDrawer() {
    $("drawer").classList.remove("hidden");
    $("drawerMask").classList.remove("hidden");
    loadConfigs();
  }

  function closeDrawer() {
    $("drawer").classList.add("hidden");
    $("drawerMask").classList.add("hidden");
    state.editing = null;
  }

  async function loadConfigs() {
    try {
      if (state.tab === "model") {
        state.modelConfigs = await Api.listModelConfigs();
      } else if (state.tab === "compact") {
        state.compactConfigs = await Api.listCompactConfigs();
      } else {
        state.prompts = await Api.listPrompts(AGENT_NAME);
      }
      renderDrawer();
    } catch (e) {
      toast(e.message, true);
    }
  }

  async function refreshModelBadge() {
    try {
      const configs = await Api.listModelConfigs();
      state.modelConfigs = configs;
      const enabled = Object.entries(configs || {}).find(([, c]) => c.enabled);
      const badge = $("modelBadge");
      if (enabled) {
        badge.textContent = enabled[1].model + " · " + enabled[0];
        badge.classList.add("enabled");
      } else {
        badge.textContent = "默认配置（application.yml）";
        badge.classList.remove("enabled");
      }
    } catch (_) {
      $("modelBadge").textContent = "配置加载失败";
    }
  }

  const modelFields = [
    { key: "apiKey", label: "API Key", type: "password", placeholder: "sk-..." },
    { key: "baseUrl", label: "Base URL", type: "text", placeholder: "https://api.deepseek.com" },
    { key: "model", label: "模型", type: "text", placeholder: "deepseek-chat" },
    { key: "temperature", label: "Temperature（0~2，可空）", type: "number", step: "0.1", half: true },
    { key: "maxTokens", label: "Max Tokens（可空）", type: "number", step: "1", half: true },
    { key: "reasoningEffort", label: "思考强度", type: "select", options: ["high", "none", "low", "max"], half: true }
  ];

  const compactFields = [
    { key: "contextWindow", label: "上下文窗口", type: "number", step: "1", half: true },
    { key: "triggerRatio", label: "触发比例（0~1）", type: "number", step: "0.05", half: true },
    { key: "toolRatio", label: "工具结果阈值比例", type: "number", step: "0.005", half: true },
    { key: "messageRatio", label: "普通消息阈值比例", type: "number", step: "0.005", half: true },
    { key: "compressRatio", label: "压缩比例", type: "number", step: "0.05", half: true },
    { key: "keepChars", label: "保留字符数", type: "number", step: "10", half: true }
  ];

  function configDefaults(tab) {
    return tab === "model"
      ? { apiKey: "", baseUrl: "https://api.deepseek.com", model: "deepseek-chat", temperature: null, maxTokens: null, reasoningEffort: "high", enabled: true }
      : { contextWindow: 128000, triggerRatio: 0.7, toolRatio: 0.01, messageRatio: 0.02, compressRatio: 0.5, keepChars: 100, enabled: true };
  }

  function renderDrawer() {
    if (state.tab === "prompt") {
      renderPrompts();
      return;
    }
    const isModel = state.tab === "model";
    const configs = isModel ? state.modelConfigs : state.compactConfigs;
    const body = $("drawerBody");
    const names = Object.keys(configs || {}).sort();
    let html =
      '<div class="cfg-note">' +
      (isModel
        ? "配置以 JSONL 文件保存在 modelconfig 目录。运行时读取第一个「已启用」的配置；全部未启用时回退到 application.yml。"
        : "配置以 JSONL 文件保存在 memorycompactconfig 目录。运行时读取第一个「已启用」的配置；全部未启用时使用内置默认值。触发条件是估算 token 超过「上下文窗口 × 触发比例」，默认 128000 × 0.7 需要很长的对话才会触发，想验证效果可把窗口调小，例如 2000。") +
      '</div><div class="cfg-toolbar"><span class="cfg-note" style="margin:0">共 ' + names.length + ' 个配置</span>' +
      '<button class="cfg-new" id="cfgNew">＋ 新建配置</button></div>';

    if (!names.length) html += '<div class="drawer-empty">暂无配置，点击「新建配置」创建</div>';
    body.innerHTML = html;

    for (const name of names) {
      const config = configs[name];
      const item = document.createElement("div");
      item.className = "cfg-item" + (state.editing === name ? " selected" : "");
      const summary = isModel
        ? (config.model || "") + " · effort " + (config.reasoningEffort || "high")
        : "window " + (config.contextWindow ?? 128000) + " · trigger " + (config.triggerRatio ?? 0.7);
      item.innerHTML =
        '<div class="cfg-item-head"><span class="cfg-name">' + esc(name) + "</span>" +
        '<span class="cfg-enabled-tag' + (config.enabled ? "" : " off") + '">' + (config.enabled ? "已启用" : "未启用") + "</span>" +
        '<button class="cfg-del">删除</button></div>' +
        '<div class="cfg-summary">' + esc(summary) + "</div>";
      item.addEventListener("click", () => {
        state.editing = name;
        renderDrawer();
      });
      item.querySelector(".cfg-del").addEventListener("click", async (e) => {
        e.stopPropagation();
        if (!confirm("删除配置「" + name + "」？")) return;
        try {
          if (isModel) await Api.deleteModelConfig(name);
          else await Api.deleteCompactConfig(name);
          if (state.editing === name) state.editing = null;
          toast("已删除");
          await loadConfigs();
          refreshModelBadge();
        } catch (err) {
          toast(err.message, true);
        }
      });
      body.appendChild(item);
    }

    $("cfgNew").addEventListener("click", () => {
      state.editing = "__new__";
      renderDrawer();
    });

    if (state.editing) renderForm(body, isModel);
  }

  function renderForm(body, isModel) {
    const isNew = state.editing === "__new__";
    const configs = isModel ? state.modelConfigs : state.compactConfigs;
    const config = isNew ? configDefaults(state.tab) : configs[state.editing];
    if (!config) {
      state.editing = null;
      return;
    }
    const fields = isModel ? modelFields : compactFields;
    const form = document.createElement("div");
    form.className = "cfg-form";
    let html = "<h3>" + (isNew ? "新建配置" : "编辑：" + esc(state.editing)) + "</h3>";
    html += '<div class="form-row"><label>配置名称（即文件名，保存后不可改）</label>' +
      '<input type="text" id="f-name" value="' + esc(isNew ? "" : state.editing) + '"' + (isNew ? ' placeholder="例如 default"' : " disabled") + "></div>";
    html += '<div class="form-grid">';
    for (const field of fields) {
      const value = config[field.key];
      html += '<div class="form-row"' + (field.half ? "" : ' style="grid-column:1/-1"') + '><label>' + esc(field.label) + "</label>";
      if (field.type === "select") {
        html += '<select id="f-' + field.key + '">' + field.options.map((op) =>
          '<option value="' + op + '"' + (value === op ? " selected" : "") + ">" + op + "</option>").join("") + "</select>";
      } else {
        html += '<input type="' + field.type + '" id="f-' + field.key + '"' +
          (field.step ? ' step="' + field.step + '"' : "") +
          (field.placeholder ? ' placeholder="' + esc(field.placeholder) + '"' : "") +
          ' value="' + (value == null ? "" : esc(String(value))) + '">';
      }
      html += "</div>";
    }
    html += "</div>";
    html += '<label class="form-check"><input type="checkbox" id="f-enabled"' + (config.enabled ? " checked" : "") + ">启用此配置（运行时读取第一个启用的配置）</label>";
    html += '<div class="form-actions"><button class="btn btn-primary" id="cfgSave">保存</button><button class="btn btn-ghost" id="cfgCancel">取消</button></div>';
    form.innerHTML = html;
    body.appendChild(form);
    form.scrollIntoView({ behavior: "smooth", block: "nearest" });

    $("cfgCancel").addEventListener("click", () => {
      state.editing = null;
      renderDrawer();
    });
    $("cfgSave").addEventListener("click", async () => {
      const name = $("f-name").value.trim();
      if (!name) {
        toast("请填写配置名称", true);
        return;
      }
      const payload = {};
      for (const field of fields) {
        const raw = $("f-" + field.key).value;
        if (field.type === "number") {
          payload[field.key] = raw === "" ? null : Number(raw);
        } else {
          payload[field.key] = raw;
        }
      }
      payload.enabled = $("f-enabled").checked;
      try {
        if (isModel) await Api.saveModelConfig(name, payload);
        else await Api.saveCompactConfig(name, payload);
        toast("已保存「" + name + "」");
        state.editing = name;
        await loadConfigs();
        refreshModelBadge();
      } catch (e) {
        toast(e.message, true);
      }
    });
  }

  function renderPrompts() {
    const body = $("drawerBody");
    const prompts = state.prompts || [];
    let html =
      '<div class="cfg-note">系统提示词以「agent名称-提示词名称-顺序.md」保存在 systemprompt 目录，按顺序拼接后作为模型的首条 system 消息。当前 agent：' +
      esc(AGENT_NAME) + "</div>" +
      '<div class="cfg-toolbar"><span class="cfg-note" style="margin:0">共 ' + prompts.length + " 个提示词</span>" +
      '<button class="cfg-new" id="promptNew">＋ 新建提示词</button></div>';
    if (!prompts.length) html += '<div class="drawer-empty">暂无提示词，点击「新建提示词」创建</div>';
    body.innerHTML = html;

    for (const prompt of prompts) {
      const key = prompt.name + "-" + prompt.order;
      const item = document.createElement("div");
      item.className = "cfg-item" + (state.promptEditing === key ? " selected" : "");
      const preview = (prompt.content || "").trim().replace(/\s+/g, " ");
      item.innerHTML =
        '<div class="cfg-item-head"><span class="cfg-name">' + esc(prompt.name) + "</span>" +
        '<span class="cfg-enabled-tag">顺序 ' + prompt.order + "</span>" +
        '<button class="cfg-del">删除</button></div>' +
        '<div class="cfg-summary">' +
        esc(preview.length > 80 ? preview.slice(0, 80) + "…" : (preview || "（空）")) + "</div>";
      item.addEventListener("click", () => {
        state.promptEditing = key;
        renderPrompts();
      });
      item.querySelector(".cfg-del").addEventListener("click", async (e) => {
        e.stopPropagation();
        if (!confirm("删除提示词「" + prompt.name + "」？")) return;
        try {
          await Api.deletePrompt(AGENT_NAME, prompt.name, prompt.order);
          if (state.promptEditing === key) state.promptEditing = null;
          toast("已删除");
          await loadConfigs();
        } catch (err) {
          toast(err.message, true);
        }
      });
      body.appendChild(item);
    }

    $("promptNew").addEventListener("click", () => {
      state.promptEditing = "__new__";
      renderPrompts();
    });

    if (state.promptEditing) renderPromptForm(body);
  }

  function renderPromptForm(body) {
    const isNew = state.promptEditing === "__new__";
    const prompt = isNew
      ? { name: "", order: 0, content: "" }
      : (state.prompts || []).find((item) => item.name + "-" + item.order === state.promptEditing);
    if (!prompt) {
      state.promptEditing = null;
      return;
    }
    const form = document.createElement("div");
    form.className = "cfg-form";
    form.innerHTML =
      "<h3>" + (isNew ? "新建提示词" : "编辑：" + esc(prompt.name)) + "</h3>" +
      '<div class="form-grid">' +
      '<div class="form-row"><label>提示词名称（即文件名，保存后不可改）</label>' +
      '<input type="text" id="p-name" value="' + esc(isNew ? "" : prompt.name) + '"' +
      (isNew ? ' placeholder="例如 base"' : " disabled") + "></div>" +
      '<div class="form-row"><label>顺序（越小越先拼接）</label>' +
      '<input type="number" step="1" min="0" id="p-order" value="' + prompt.order + '"' +
      (isNew ? "" : " disabled") + "></div></div>" +
      '<div class="form-row"><label>提示词内容</label>' +
      '<textarea id="p-content" rows="12" placeholder="你是一个……的智能体">' + esc(prompt.content || "") + "</textarea></div>" +
      '<div class="form-actions"><button class="btn btn-primary" id="promptSave">保存</button>' +
      '<button class="btn btn-ghost" id="promptCancel">取消</button></div>';
    body.appendChild(form);
    form.scrollIntoView({ behavior: "smooth", block: "nearest" });

    $("promptCancel").addEventListener("click", () => {
      state.promptEditing = null;
      renderPrompts();
    });
    $("promptSave").addEventListener("click", async () => {
      const name = isNew ? $("p-name").value.trim() : prompt.name;
      const order = isNew ? Number($("p-order").value || 0) : prompt.order;
      if (!name) {
        toast("请填写提示词名称", true);
        return;
      }
      if (!Number.isInteger(order) || order < 0) {
        toast("顺序必须是非负整数", true);
        return;
      }
      try {
        await Api.savePrompt(AGENT_NAME, name, order, $("p-content").value);
        toast("已保存「" + name + "」");
        state.promptEditing = name + "-" + order;
        await loadConfigs();
      } catch (e) {
        toast(e.message, true);
      }
    });
  }

  $("newChatBtn").addEventListener("click", createSession);
  $("workspaceBtn").addEventListener("click", openWorkspacePicker);
  $("wsClose").addEventListener("click", closeWorkspacePicker);
  $("wsMask").addEventListener("click", closeWorkspacePicker);
  $("wsConfirm").addEventListener("click", confirmWorkspace);
  $("wsUp").addEventListener("click", () => {
    if (state.wsDir && state.wsDir.parent) loadWorkspaceDir(state.wsDir.parent);
  });
  $("sendBtn").addEventListener("click", send);
  $("stopBtn").addEventListener("click", stop);
  $("settingsBtn").addEventListener("click", openDrawer);
  $("drawerClose").addEventListener("click", closeDrawer);
  $("drawerMask").addEventListener("click", closeDrawer);
  $("sidebarToggle").addEventListener("click", () => $("sidebar").classList.toggle("open"));
  $("themeBtn").addEventListener("click", () => {
    applyTheme(document.body.hasAttribute("data-ds-dark-theme") ? "light" : "dark");
  });

  document.querySelectorAll(".tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      document.querySelectorAll(".tab").forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      state.tab = tab.dataset.tab;
      state.editing = null;
      state.promptEditing = null;
      loadConfigs();
    });
  });

  inputEl.addEventListener("input", autoResize);
  inputEl.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
      e.preventDefault();
      send();
    }
  });

  chatInner.addEventListener("click", (e) => {
    const copy = e.target.closest(".code-copy");
    if (copy) {
      const code = copy.closest(".code-block").querySelector("pre code");
      navigator.clipboard.writeText(code.textContent).then(() => toast("代码已复制"));
      return;
    }
    const toolHead = e.target.closest(".tool-head");
    if (toolHead) {
      toolHead.parentElement.classList.toggle("open");
      return;
    }
    const reasoningToggle = e.target.closest(".reasoning-toggle");
    if (reasoningToggle) {
      reasoningToggle.parentElement.classList.toggle("open");
      return;
    }
    const emptyCard = e.target.closest(".empty-card");
    if (emptyCard) {
      inputEl.value = emptyCard.dataset.prompt;
      autoResize();
      inputEl.focus();
    }
  });

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeDrawer();
  });

  applyTheme(localStorage.getItem("wayfarer-theme") || "light");
  renderWorkspace();
  loadSessions();
  refreshModelBadge();
})();
