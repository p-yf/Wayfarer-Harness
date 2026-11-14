(function () {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const chatEl = $("chat");
  const chatInner = $("chatInner");
  const inputEl = $("input");
  const emptyStateEl = $("emptyState");
  const esc = window.Markdown.escapeHtml;
  const AGENT_NAME = "Wayfarer";

  const state = {
    sessions: [],
    // 每个会话一份运行视图，流式输出不会因为切换会话而中断或串台
    views: {},
    currentId: null,
    hitlJson: "[]",
    hitlPending: [],
    // 已经提醒过的待确认项，避免轮询重复响铃
    hitlIds: new Set(),
    tab: "model",
    modelConfigs: {},
    compactConfigs: {},
    prompts: [],
    promptEditing: null,
    skillPath: "",
    skillParent: null,
    skillEntries: [],
    skillFile: null,
    skillCreating: null,
    editing: null,
    workspace: "",
    pendingWorkspace: "",
    // 编码工具审批级别：all 全部拦截、workspacebased 仅放行工作区内读写、no 不拦截
    approval: "no",
    pendingApproval: "",
    wsDir: null,
    metadata: {}
  };

  /** 会话视图：块列表、工具入参、运行状态与流式订阅，按会话隔离 */
  function viewOf(sessionId) {
    return state.views[sessionId] ||
      (state.views[sessionId] = { blocks: [], metadata: {}, running: false, loading: null, abort: null });
  }

  function currentView() {
    return state.currentId ? viewOf(state.currentId) : null;
  }

  function currentBlocks() {
    const view = currentView();
    return view ? view.blocks : [];
  }

  function isRunning(view) {
    return !!(view && view.running);
  }

  function anyRunning() {
    return Object.values(state.views).some(isRunning);
  }

  function toast(message, isError) {
    const el = document.createElement("div");
    el.className = "toast" + (isError ? " error" : "");
    el.textContent = message;
    $("toastWrap").appendChild(el);
    setTimeout(() => el.remove(), 3600);
  }

  /**
   * 提醒钩子：agent 执行完毕、执行出错、出现审批时各响一次提示音。
   * 音频上下文按需创建，首个用户操作之后才可能播放；不支持时静默跳过。
   */
  const REMIND_TONES = {
    done: [880, 1175],
    error: [440, 330],
    approval: [1046, 1318]
  };
  let audioCtx = null;

  function remind(kind) {
    const tones = REMIND_TONES[kind];
    if (!tones) return;
    try {
      if (!audioCtx) {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (!Ctx) return;
        audioCtx = new Ctx();
      }
      if (audioCtx.state === "suspended") audioCtx.resume();
      const now = audioCtx.currentTime;
      tones.forEach((freq, index) => {
        const start = now + index * 0.16;
        const oscillator = audioCtx.createOscillator();
        const gain = audioCtx.createGain();
        oscillator.type = "sine";
        oscillator.frequency.value = freq;
        gain.gain.setValueAtTime(0.0001, start);
        gain.gain.exponentialRampToValueAtTime(0.08, start + 0.02);
        gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.14);
        oscillator.connect(gain).connect(audioCtx.destination);
        oscillator.start(start);
        oscillator.stop(start + 0.15);
      });
    } catch (_) {
      // 浏览器不支持或用户未解锁音频时忽略
    }
  }

  /**
   * 首次交互时解锁音频，否则浏览器会把之后异步播放的提示音静音。
   */
  function unlockAudio() {
    try {
      if (!audioCtx) {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        if (Ctx) audioCtx = new Ctx();
      }
      if (audioCtx && audioCtx.state === "suspended") audioCtx.resume();
    } catch (_) {
      // 忽略不支持音频的环境
    }
  }
  document.addEventListener("pointerdown", unlockAudio, { once: true });
  document.addEventListener("keydown", unlockAudio, { once: true });

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
    // 名称由后端维护：新建时是默认名，第一条用户消息后改为该消息内容
    return meta.name || "未命名会话";
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
      const running = isRunning(state.views[meta.id]);
      item.className = "session-item" + (meta.id === state.currentId ? " active" : "");
      item.innerHTML =
        '<div class="s-title">' + esc(sessionTitle(meta)) + '</div>' +
        '<div class="s-time">' + esc(fmtTime(meta.createdAt)) +
        (running ? '<span class="s-running" title="正在运行"></span>' : "") +
        '</div>' +
        '<button class="s-copy" title="复制会话"><svg viewBox="0 0 16 16" width="13" height="13"><rect x="5.2" y="5.2" width="7.6" height="7.6" rx="1.6" stroke="currentColor" stroke-width="1.2" fill="none"/><path d="M10.4 3.2H4.6a1.6 1.6 0 0 0-1.6 1.6v5.8" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linecap="round"/></svg></button>' +
        '<button class="s-del" title="删除会话"><svg viewBox="0 0 16 16" width="13" height="13"><path d="M3 4h10M6.5 4V2.8h3V4M4.5 4l.6 9h5.8l.6-9M6.8 6.5v4M9.2 6.5v4" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linecap="round"/></svg></button>';
      item.addEventListener("click", () => openSession(meta.id));
      item.querySelector(".s-copy").addEventListener("click", async (e) => {
        e.stopPropagation();
        if (isRunning(state.views[meta.id])) {
          toast("该会话正在运行中，请先停止", true);
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
      item.querySelector(".s-del").addEventListener("click", (e) => {
        e.stopPropagation();
        askDelete(item, meta);
      });
      list.appendChild(item);
    }
  }

  /**
   * 删除确认直接展开在会话项原位，替代浏览器原生 confirm。
   */
  function askDelete(item, meta) {
    if (item.querySelector(".s-confirm")) return;
    item.classList.add("confirming");
    const box = document.createElement("div");
    box.className = "s-confirm";
    box.innerHTML = '<span>确定删除？</span>' +
        '<button type="button" class="s-no">取消</button>'+
      '<button type="button" class="s-yes">删除</button>';
    item.appendChild(box);
    box.addEventListener("click", (e) => e.stopPropagation());
    box.querySelector(".s-no").addEventListener("click", () => {
      box.remove();
      item.classList.remove("confirming");
    });
    box.querySelector(".s-yes").addEventListener("click", async () => {
      const yes = box.querySelector(".s-yes");
      yes.disabled = true;
      try {
        await cancelRun(meta.id);
        await Api.deleteSession(meta.id);
        delete state.views[meta.id];
        if (state.currentId === meta.id) {
          state.currentId = null;
          renderSessions();
          renderHitl(state.hitlPending);
          renderChat();
          syncComposer();
        }
        await loadSessions();
        toast("会话已删除");
      } catch (err) {
        toast(err.message, true);
        yes.disabled = false;
      }
    });
  }

  async function createSession() {
    try {
      const meta = await Api.createSession("未命名会话");
      await savePendingMeta(meta.id);
      await loadSessions();
      await openSession(meta.id);
      inputEl.focus();
    } catch (e) {
      toast(e.message, true);
    }
  }

  /**
   * 切换会话。内存里已有视图（历史消息或正在流式输出）时直接渲染，
   * 避免重新拉取历史打断正在进行的输出；forceReload 用于 fork/reset 后重读。
   */
  async function openSession(id, forceReload) {
    if (id === state.currentId && !forceReload) return;
    state.currentId = id;
    const view = viewOf(id);
    renderSessions();
    renderHitl(state.hitlPending);
    syncComposer();
    if (!forceReload && (view.blocks.length || view.running)) {
      applyViewMeta(view);
      renderChat();
      scrollChat(true);
    } else {
      view.blocks = [];
      view.toolArgs = {};
      renderChat();
      view.loading = loadHistory(id);
      await view.loading;
    }
    if (window.innerWidth <= 900) $("sidebar").classList.remove("open");
  }

  async function loadHistory(id) {
    try {
      const [meta, messages] = await Promise.all([Api.sessionMeta(id), Api.sessionMessages(id)]);
      const view = viewOf(id);
      // 历史还没返回就开跑了，以实时视图为准，避免覆盖本轮输出
      if (view.running) return;
      view.blocks = historyToBlocks(messages);
      view.metadata = (meta && meta.metadata) || {};
      if (state.currentId !== id) return;
      applyViewMeta(view);
      renderChat();
      scrollChat(true);
    } catch (e) {
      toast(e.message, true);
    }
  }

  /** 工作区等元数据跟随当前会话切换 */
  function applyViewMeta(view) {
    state.metadata = view.metadata || {};
    state.workspace = state.metadata.workspace || "";
    state.approval = state.metadata.approval || "no";
    renderWorkspace();
  }

  const WORKSPACE_NAME_MAX = 15;
  const APPROVAL_LABELS = { all: "全部拦截", workspacebased: "仅工作区内", no: "不拦截" };

  /**
   * 工作区只展示目录名，最长 15 个字符，完整路径放在 title 里。
   */
  function renderWorkspace() {
    const name = state.workspace ? folderName(state.workspace) : "";
    $("workspaceName").textContent = name
      ? (name.length > WORKSPACE_NAME_MAX ? name.slice(0, WORKSPACE_NAME_MAX) + "…" : name)
      : "工作区选择";
    $("workspaceBtn").title = state.workspace ? "工作区：" + state.workspace : "选择工作区";
    $("workspaceBtn").classList.toggle("set", !!state.workspace);
    renderApproval();
  }

  function renderApproval() {
    $("approvalName").textContent = APPROVAL_LABELS[state.approval] || APPROVAL_LABELS.no;
    $("approvalBtn").classList.toggle("set", state.approval !== "no");
    for (const option of $("permMenu").querySelectorAll(".perm-option")) {
      option.classList.toggle("active", option.dataset.mode === state.approval);
      // 工作区模式必须先选工作区，否则无法判断路径边界
      const disabled = option.dataset.mode === "workspacebased" && !state.workspace;
      option.disabled = disabled;
      option.classList.toggle("disabled", disabled);
    }
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
      const view = currentView();
      view.metadata = Object.assign({}, view.metadata, { workspace: path });
      state.metadata = view.metadata;
      await Api.updateSessionMetadata(state.currentId, state.metadata);
      toast("工作区已保存");
    } catch (e) {
      toast(e.message, true);
    }
  }

  /**
   * 审批级别随会话元数据保存；还没有会话时先记下，创建会话时一并写入。
   */
  async function setApproval(mode) {
    state.approval = mode;
    renderApproval();
    const view = currentView();
    if (!view) {
      state.pendingApproval = mode;
      return;
    }
    view.metadata = Object.assign({}, view.metadata, { approval: mode });
    state.metadata = view.metadata;
    try {
      await Api.updateSessionMetadata(state.currentId, view.metadata);
    } catch (e) {
      toast(e.message, true);
    }
  }

  /**
   * 新会话创建前选择的工作区与审批级别，统一在创建后写入会话元数据。
   */
  async function savePendingMeta(sessionId) {
    const meta = {};
    if (state.pendingWorkspace) meta.workspace = state.pendingWorkspace;
    if (state.pendingApproval) meta.approval = state.pendingApproval;
    if (!Object.keys(meta).length) return;
    try {
      await Api.updateSessionMetadata(sessionId, meta);
      state.pendingWorkspace = "";
      state.pendingApproval = "";
    } catch (e) {
      toast(e.message, true);
    }
  }

  /**
   * 助手消息上的轮次操作：从此分支（fork）或回退本轮（reset）。
   */
  async function messageAction(action, block) {
    const view = currentView();
    if (!view || !state.currentId || !block.traceId) return;
    if (isRunning(view)) {
      toast("当前会话正在运行中，请先停止", true);
      return;
    }

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
      await openSession(state.currentId, true);
      // 把该轮的问题放回输入框，方便改完直接重发
      if (query) inputEl.value = query;
      inputEl.focus();
      toast("已回退到本轮之前");
    } catch (e) {
      toast(e.message, true);
    }
  }

  function precedingUserText(block) {
    const blocks = currentBlocks();
    for (let i = blocks.indexOf(block); i >= 0; i--) {
      if (blocks[i].kind === "user") return blocks[i].content;
    }
    return "";
  }

  function historyToBlocks(messages) {
    const blocks = [];
    const toolsById = {};
    const headTraces = new Set();
    // 每轮助手消息中工具卡片在 blocks 中的起始下标，用于把调用记录插在文字之后、工具之前
    const toolStart = {};
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
        toolStart[msg.traceId] = blocks.length;
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
      } else if (msg.type === "COMPACT") {
        blocks.push({ kind: "compact" });
      } else if (msg.type === "EXTRADATA") {
        // 与实时流保持一致：调用记录紧跟本轮文字，排在该轮工具卡片之前
        const end = {
          kind: "end",
          finishReason: content.finishReason,
          usage: content.usage,
          durationMs: content.durationMs,
          traceId: msg.traceId
        };
        const index = toolStart[msg.traceId];
        if (typeof index === "number") {
          blocks.splice(index, 0, end);
        } else {
          blocks.push(end);
        }
        delete toolStart[msg.traceId];
      }
    }
    return blocks;
  }

  /**
   * 同一 trace 只保留一个 Wayfarer 头像块与一组轮次操作。
   */
  function ensureHead(view, traceId) {
    if (view.blocks.some((block) => block.kind === "head" && block.traceId === traceId)) return;
    pushBlock(view, { kind: "head", traceId: traceId });
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

  function lastPlanBlock(view) {
    for (let i = view.blocks.length - 1; i >= 0; i--) {
      if (view.blocks[i].kind === "plan") return view.blocks[i];
    }
    return null;
  }

  function applyPlanResult(view, name, result) {
    if (!PLAN_TOOLS[name]) return;
    const text = typeof result === "string" ? result : "";
    let plan = parsePlanText(text);
    if (!plan && text.indexOf("计划已清除") >= 0) {
      const previous = lastPlanBlock(view);
      if (!previous) return;
      const finished = {};
      for (const key of Object.keys(previous.plan || {})) {
        finished[key] = { content: (previous.plan[key] || {}).content || "", status: "done" };
      }
      plan = finished;
    }
    if (!plan) return;
    const existing = lastPlanBlock(view);
    if (existing) {
      existing.plan = plan;
      swapBlock(view, existing);
    } else {
      pushBlock(view, { kind: "plan", plan: plan });
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
        '<div class="msg-head"><img class="avatar" src="/favicon.svg" alt="">Wayfarer' +
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
    const blocks = currentBlocks();
    chatInner.innerHTML = "";
    if (!blocks.length) {
      chatInner.appendChild(emptyStateEl);
      emptyStateEl.classList.remove("hidden");
      return;
    }
    for (const block of blocks) {
      chatInner.appendChild(renderBlock(block));
    }
    scrollChat();
  }

  /** 块只挂到当前会话的 DOM；后台会话的流式输出仅累积数据，切回时重建 DOM */
  function pushBlock(view, block) {
    view.blocks.push(block);
    if (view !== currentView()) return block;
    if (view.blocks.length === 1) chatInner.innerHTML = "";
    chatInner.appendChild(renderBlock(block));
    scrollChat();
    return block;
  }

  /**
   * 就地替换某个块对应的 DOM。renderBlock 会把 block.el 指向新节点，
   * 所以必须先留存旧节点，否则替换会作用在未插入文档的新节点上而静默失效。
   */
  function swapBlock(view, block) {
    if (view !== currentView()) return null;
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
      // 已切到别的会话时旧节点已脱离文档，跳过即可，数据仍在块里等切回重建
      if (!block.el || !block.el.isConnected) continue;
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

  function handleEvent(view, name, data) {
    if (name === "delta") {
      let block = view.blocks[view.blocks.length - 1];
      if (!block || block.kind !== "text") {
        ensureHead(view, data.traceId);
        block = pushBlock(view, {
          kind: "text",
          content: "",
          traceId: data.traceId
        });
        const body = block.el && block.el.querySelector(".msg-body");
        if (body) body.classList.add("cursor-blink");
      }
      block.content += data.content || "";
      scheduleRender(block);
    } else if (name === "reasoning") {
      let block = view.blocks[view.blocks.length - 1];
      if (!block || block.kind !== "reasoning") {
        ensureHead(view, data.traceId);
        block = pushBlock(view, { kind: "reasoning", content: "", open: true, traceId: data.traceId });
      }
      block.content += data.content || "";
      scheduleRender(block);
    } else if (name === "tool_call") {
      const call = data.call || {};
      ensureHead(view, data.traceId);
      pushBlock(view, { kind: "tool", id: call.id, name: call.name, arguments: call.arguments, result: null, open: true, traceId: data.traceId });
    } else if (name === "tool_result") {
      let block = null;
      for (let i = view.blocks.length - 1; i >= 0; i--) {
        const candidate = view.blocks[i];
        if (candidate.kind === "tool" && candidate.id === data.toolCallId) {
          block = candidate;
          break;
        }
      }
      if (block) {
        block.result = data.content || "";
        block.open = false;
        swapBlock(view, block);
      } else {
        pushBlock(view, { kind: "tool", id: data.toolCallId, name: "工具结果", arguments: null, result: data.content || "", open: false });
      }
      applyPlanResult(view, block ? block.name : "", data.content || "");
      scrollChat();
    } else if (name === "trace_data") {
      // 每轮模型调用的耗时与用量，后端已持久化为 EXTRADATA
      paintDirty();
      // 本轮可能只调工具、没有任何文字输出，这里先补头像块，避免耗时行跑到头像上面
      ensureHead(view, data.traceId);
      pushBlock(view, {
        kind: "end",
        finishReason: data.finishReason,
        usage: data.usage,
        durationMs: data.durationMs,
        traceId: data.traceId
      });
    } else if (name === "message_end") {
      // 先把待渲染的增量刷出来，再收尾，避免最后一段文字丢失
      paintDirty();
      for (const block of view.blocks) {
        if (block.kind === "text" && block.el) {
          const body = block.el.querySelector(".msg-body");
          if (body) body.classList.remove("cursor-blink");
        }
        if (block.kind === "reasoning") {
          block.open = false;
          if (block.el) block.el.classList.remove("open");
        }
      }
    } else if (name === "other") {
      // 压缩等内部事件用提示条展示，其余非字符串负载忽略
      if (data && typeof data === "object" && data.type === "compact") {
        pushBlock(view, { kind: "compact" });
      } else if (typeof data === "string") {
        pushBlock(view, { kind: "other", data: data });
      }
    }
  }

  /** 运行状态记在会话视图上，界面只反映当前会话 */
  function markRunning(view, running) {
    view.running = running;
    renderSessions();
    syncComposer();
    if (anyRunning()) startHitlPolling();
    else stopHitlPolling();
  }

  function syncComposer() {
    const running = isRunning(currentView());
    $("sendBtn").classList.toggle("hidden", running);
    $("stopBtn").classList.toggle("hidden", !running);
    inputEl.disabled = running;
    $("composerHint").textContent = running ? "行者 正在工作…" : "";
  }

  async function send() {
    const query = inputEl.value.trim();
    if (!query) return;
    const sessionId = state.currentId || await createSessionForRun();
    if (!sessionId) return;
    const view = viewOf(sessionId);
    if (view.running) return;
    // 历史还在加载时先等它落地，避免新回合覆盖旧消息
    if (view.loading) await view.loading;

    inputEl.value = "";
    autoResize();
    pushBlock(view, { kind: "user", content: query });
    if (view === currentView()) scrollChat(true);
    markRunning(view, true);
    view.abort = new AbortController();
    const payload = {
      sessionId: sessionId,
      userquery: query,
      agentName: AGENT_NAME,
      approval: state.approval || "no"
    };
    if (state.workspace) payload.workspace = state.workspace;
    let aborted = false;
    let failed = false;
    try {
      await Api.streamAgent(payload, { onEvent: (name, data) => handleEvent(view, name, data) }, view.abort.signal);
    } catch (e) {
      if (e.name === "AbortError") {
        aborted = true;
        pushBlock(view, { kind: "other", data: "已停止" });
      } else {
        failed = true;
        const detail = e.message.indexOf("未配置模型") >= 0
          ? e.message + "（点右上角「设置」添加并启用一个模型配置）"
          : e.message;
        toast(detail, true);
        pushBlock(view, { kind: "other", data: "出错：" + detail });
      }
    } finally {
      if (view === currentView()) {
        chatInner.querySelectorAll(".cursor-blink").forEach((el) => el.classList.remove("cursor-blink"));
        inputEl.focus();
      }
      view.abort = null;
      markRunning(view, false);
      // 提醒钩子：正常结束与出错分别响一次，用户主动终止不打扰
      if (!aborted) remind(failed ? "error" : "done");
      // 后端会在第一条消息后自动命名会话，结束后刷新列表拿到新名称
      loadSessions();
    }
  }

  /** 没有当前会话时先建一个，返回新会话 id；失败返回 null */
  async function createSessionForRun() {
    try {
      const meta = await Api.createSession("未命名会话");
      await savePendingMeta(meta.id);
      viewOf(meta.id).metadata = Object.assign({}, state.metadata,
        { workspace: state.workspace, approval: state.approval });
      await loadSessions();
      await openSession(meta.id);
      return meta.id;
    } catch (e) {
      toast(e.message, true);
      return null;
    }
  }

  /** 终止指定会话的运行；当前会话的界面由 send 的 finally 收尾 */
  async function cancelRun(sessionId) {
    const view = state.views[sessionId];
    if (!isRunning(view)) return;
    try {
      await Api.stopAgent(sessionId);
    } catch (e) {
      toast(e.message, true);
    }
    if (view.abort) view.abort.abort();
  }

  async function stop() {
    if (state.currentId) await cancelRun(state.currentId);
  }

  let hitlTimer = 0;

  function startHitlPolling() {
    if (hitlTimer) return;
    pollHitl();
    hitlTimer = setInterval(pollHitl, 1200);
  }

  function stopHitlPolling() {
    if (hitlTimer) {
      clearInterval(hitlTimer);
      hitlTimer = 0;
    }
    // 运行结束后不会有新的待确认项，清掉残留卡片
    state.hitlJson = "";
    state.hitlIds = new Set();
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

  const warnIcon = '<svg viewBox="0 0 16 16" width="15" height="15"><path d="M8 1.8L15 14H1z" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round"/><path d="M8 6.2v3.4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/><circle cx="8" cy="11.8" r="0.8" fill="currentColor"/></svg>';

  /**
   * 后端未带 metadata 时的兜底审批页，保持与编码工具审批一致的允许/拒绝两项。
   */
  const DEFAULT_HITL_SHEET = { sheet1: { key1: "允许", key2: "拒绝", style: "radio group" } };

  /**
   * 审批卡片从页面底部弹出。metadata 的每一页渲染一组单选按钮，
   * 提交时把「页名 → 选中的键」原样回传，后端据此决定是否放行工具。
   */
  function renderHitl(pending) {
    state.hitlPending = pending;
    // 新出现的待确认项响一次提示音，轮询重绘不重复响
    const ids = new Set(pending.map((item) => item.toolCallId));
    if ([...ids].some((id) => !state.hitlIds.has(id))) remind("approval");
    state.hitlIds = ids;
    const area = $("hitlArea");
    area.innerHTML = "";
    // 待确认项属于具体会话，切到别的会话时不展示
    const items = pending.filter((item) => !item.sessionId || item.sessionId === state.currentId);
    area.classList.toggle("show", items.length > 0);
    items.forEach((item, index) => area.appendChild(hitlCard(item, index)));
  }

  function hitlCard(item, index) {
    const card = document.createElement("div");
    card.className = "hitl-card";
    const metadata = item.metadata && Object.keys(item.metadata).length
      ? item.metadata
      : DEFAULT_HITL_SHEET;
    const sheetNames = Object.keys(metadata);
    let sheets = "";
    for (const sheetName of sheetNames) {
      const sheet = metadata[sheetName] || {};
      const radioName = "hitl-" + index + "-" + sheetName;
      let options = "";
      for (const [key, label] of Object.entries(sheet)) {
        if (key === "style") continue;
        options +=
          '<label class="hitl-option"><input type="radio" name="' + esc(radioName) + '" value="' + esc(key) + '">' +
          "<span>" + esc(String(label)) + "</span></label>";
      }
      sheets +=
        '<div class="hitl-sheet" data-sheet="' + esc(sheetName) + '">' +
        (sheetNames.length > 1 ? '<div class="hitl-sheet-title">' + esc(sheetName) + "</div>" : "") +
        options + "</div>";
    }
    card.innerHTML =
      '<div class="hitl-title"><span class="warn">' + warnIcon + "</span>工具 <b>" +
      esc(item.toolName || "") + "</b> 请求确认</div>" +
      (item.arguments ? '<pre class="hitl-args">' + esc(prettyArgs(item.arguments)) + "</pre>" : "") +
      sheets +
      '<div class="hitl-actions"><button class="btn btn-primary hitl-submit">提交</button></div>';

    card.querySelector(".hitl-submit").addEventListener("click", () => {
      const decision = {};
      for (const group of card.querySelectorAll(".hitl-sheet")) {
        const checked = group.querySelector("input:checked");
        if (!checked) {
          toast("请先选择「" + group.dataset.sheet + "」", true);
          return;
        }
        decision[group.dataset.sheet] = checked.value;
      }
      decide(item.toolCallId, decision, card);
    });
    return card;
  }

  async function decide(toolCallId, decision, card) {
    const submit = card && card.querySelector(".hitl-submit");
    if (submit) submit.disabled = true;
    try {
      await Api.hitlDecide(toolCallId, decision);
      toast("已提交审批");
    } catch (e) {
      toast(e.message, true);
      if (submit) submit.disabled = false;
    }
    state.hitlJson = "";
    pollHitl();
  }

  function autoResize() {
    inputEl.style.height = "auto";
    inputEl.style.height = Math.min(inputEl.scrollHeight, 200) + "px";
  }

  /**
   * 打开右侧面板：model/compact 是设置页，prompt/skills 从侧边栏直接进入。
   */
  function openDrawer(tab) {
    state.tab = tab || "model";
    state.editing = null;
    state.promptEditing = null;
    resetSkillBrowser();
    $("drawer").classList.remove("hidden");
    $("drawerMask").classList.remove("hidden");
    const standalone = state.tab === "prompt" || state.tab === "skills";
    $("drawerTitle").textContent = { prompt: "系统提示词", skills: "Skills" }[state.tab] || "设置";
    $("drawerTabs").classList.toggle("hidden", standalone);
    document.querySelectorAll(".tab").forEach((item) => {
      item.classList.toggle("active", item.dataset.tab === state.tab);
    });
    loadConfigs();
  }

  function closeDrawer() {
    $("drawer").classList.add("hidden");
    $("drawerMask").classList.add("hidden");
    state.editing = null;
    resetSkillBrowser();
  }

  function resetSkillBrowser() {
    state.skillPath = "";
    state.skillParent = null;
    state.skillEntries = [];
    state.skillFile = null;
    state.skillCreating = null;
  }

  async function loadConfigs() {
    try {
      if (state.tab === "model") {
        state.modelConfigs = await Api.listModelConfigs();
      } else if (state.tab === "compact") {
        state.compactConfigs = await Api.listCompactConfigs();
      } else if (state.tab === "skills") {
        const tree = await Api.skillTree(state.skillPath);
        state.skillPath = tree.path || "";
        state.skillParent = tree.parent;
        state.skillEntries = tree.entries || [];
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
    if (state.tab === "skills") {
      renderSkills();
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

  const fileIcon = '<svg viewBox="0 0 16 16" width="14" height="14"><path d="M4 1.8h5l3 3v9.4H4z" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round"/><path d="M9 1.8v3h3" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linejoin="round"/></svg>';

  /** 当前目录下的相对路径 */
  function skillJoin(name) {
    return state.skillPath ? state.skillPath + "/" + name : name;
  }

  async function enterSkillDir(path) {
    state.skillPath = path || "";
    state.skillFile = null;
    state.skillCreating = null;
    await loadConfigs();
  }

  async function openSkillFile(path) {
    try {
      state.skillFile = await Api.skillRead(path);
      renderSkills();
    } catch (e) {
      toast(e.message, true);
    }
  }

  function skillSize(bytes) {
    if (bytes == null) return "";
    return bytes < 1024 ? bytes + " B" : Math.ceil(bytes / 1024) + " KB";
  }

  /**
   * Skills 目录浏览器：面包屑导航、目录/文件列表、新建与文本编辑。
   */
  function renderSkills() {
    const body = $("drawerBody");
    const entries = state.skillEntries || [];
    const crumbs = state.skillPath ? state.skillPath.split("/") : [];
    let html =
      '<div class="cfg-note">以文件系统方式浏览 skills 目录：点击文件夹进入，点击文件编辑。只有包含 SKILL.md 的技能文件夹会被注入系统提示词。</div>' +
      '<div class="fs-toolbar"><div class="fs-crumbs">' +
      '<button type="button" class="fs-crumb" data-path="">skills</button>' +
      crumbs.map((name, index) =>
        '<span class="fs-sep">/</span><button type="button" class="fs-crumb" data-path="' +
        esc(crumbs.slice(0, index + 1).join("/")) + '">' + esc(name) + "</button>").join("") +
      "</div>" +
      '<button type="button" class="cfg-new" id="skillNewDir">＋ 文件夹</button>' +
      '<button type="button" class="cfg-new" id="skillNewFile">＋ 文件</button></div>';
    body.innerHTML = html;

    for (const crumb of body.querySelectorAll(".fs-crumb")) {
      crumb.addEventListener("click", () => enterSkillDir(crumb.dataset.path));
    }
    if (state.skillCreating) body.appendChild(skillCreateRow());
    body.appendChild(skillEntryList(entries));
    if (state.skillFile) renderSkillEditor(body);

    $("skillNewDir").addEventListener("click", () => startSkillCreate("dir"));
    $("skillNewFile").addEventListener("click", () => startSkillCreate("file"));
  }

  function skillEntryList(entries) {
    const list = document.createElement("div");
    list.className = "fs-list";
    if (state.skillPath) {
      list.appendChild(skillRow({ name: "..", path: state.skillParent || "", directory: true, up: true }));
    }
    for (const entry of entries) {
      list.appendChild(skillRow(entry));
    }
    if (!entries.length && !state.skillPath) {
      const empty = document.createElement("div");
      empty.className = "drawer-empty";
      empty.textContent = "skills 目录还是空的，先新建文件夹";
      list.appendChild(empty);
    } else if (!entries.length && state.skillPath) {
      const empty = document.createElement("div");
      empty.className = "drawer-empty";
      empty.textContent = "空目录";
      list.appendChild(empty);
    }
    return list;
  }

  function skillRow(entry) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "fs-row";
    row.innerHTML =
      '<span class="fs-icon">' + (entry.directory ? wsFolderIcon : fileIcon) + "</span>" +
      '<span class="fs-name">' + esc(entry.name) + "</span>" +
      (entry.directory ? "" : '<span class="fs-size">' + skillSize(entry.size) + "</span>") +
      (entry.up ? "" : '<span class="fs-del" title="删除">×</span>');
    row.addEventListener("click", () => {
      if (entry.directory) enterSkillDir(entry.path);
      else openSkillFile(entry.path);
    });
    const remove = row.querySelector(".fs-del");
    if (remove) {
      remove.addEventListener("click", async (e) => {
        e.stopPropagation();
        if (!confirm("删除「" + entry.name + "」？文件夹会连同内部文件一起删除。")) return;
        try {
          await Api.skillDelete(entry.path);
          if (state.skillFile && state.skillFile.path === entry.path) state.skillFile = null;
          toast("已删除");
          await loadConfigs();
        } catch (err) {
          toast(err.message, true);
        }
      });
    }
    return row;
  }

  function startSkillCreate(kind) {
    state.skillCreating = kind;
    renderSkills();
    const input = $("fsName");
    if (input) {
      input.focus();
      input.select();
    }
  }

  function skillCreateRow() {
    const row = document.createElement("div");
    row.className = "fs-create";
    const placeholder = state.skillCreating === "dir" ? "文件夹名称" : "文件名，例如 SKILL.md";
    row.innerHTML = '<input type="text" id="fsName" placeholder="' + placeholder + '">' +
      '<button type="button" class="btn btn-primary" id="fsCreateOk">创建</button>' +
      '<button type="button" class="btn btn-ghost" id="fsCreateCancel">取消</button>';
    row.querySelector("#fsCreateCancel").addEventListener("click", () => {
      state.skillCreating = null;
      renderSkills();
    });
    row.querySelector("#fsCreateOk").addEventListener("click", async () => {
      const name = $("fsName").value.trim();
      if (!name) {
        toast("请填写名称", true);
        return;
      }
      const path = skillJoin(name);
      const kind = state.skillCreating;
      try {
        if (kind === "dir") {
          await Api.skillMkdir(path);
        } else {
          await Api.skillWrite(path, "");
        }
        state.skillCreating = null;
        await loadConfigs();
        if (kind === "file") await openSkillFile(path);
      } catch (e) {
        toast(e.message, true);
      }
    });
    return row;
  }

  function renderSkillEditor(body) {
    const form = document.createElement("div");
    form.className = "cfg-form";
    form.innerHTML =
      "<h3>编辑：" + esc(state.skillFile.path) + "</h3>" +
      '<div class="form-row"><textarea id="sfContent" rows="14"></textarea></div>' +
      '<div class="form-actions"><button class="btn btn-primary" id="sfSave">保存</button>' +
      '<button class="btn btn-ghost" id="sfClose">关闭</button></div>';
    body.appendChild(form);
    const textarea = form.querySelector("#sfContent");
    textarea.value = state.skillFile.content || "";
    form.scrollIntoView({ behavior: "smooth", block: "nearest" });

    form.querySelector("#sfClose").addEventListener("click", () => {
      state.skillFile = null;
      renderSkills();
    });
    form.querySelector("#sfSave").addEventListener("click", async () => {
      try {
        await Api.skillWrite(state.skillFile.path, textarea.value);
        state.skillFile.content = textarea.value;
        toast("已保存");
      } catch (e) {
        toast(e.message, true);
      }
    });
  }

  $("newChatBtn").addEventListener("click", createSession);
  $("promptNavBtn").addEventListener("click", () => openDrawer("prompt"));
  $("skillsNavBtn").addEventListener("click", () => openDrawer("skills"));
  $("workspaceBtn").addEventListener("click", openWorkspacePicker);
  $("approvalBtn").addEventListener("click", (e) => {
    e.stopPropagation();
    renderApproval();
    $("permMenu").classList.toggle("hidden");
  });
  $("permMenu").addEventListener("click", (e) => {
    const option = e.target.closest(".perm-option");
    if (!option || option.disabled) return;
    $("permMenu").classList.add("hidden");
    setApproval(option.dataset.mode);
  });
  document.addEventListener("click", (e) => {
    if (!e.target.closest("#approvalBtn") && !e.target.closest("#permMenu")) {
      $("permMenu").classList.add("hidden");
    }
  });
  $("wsClose").addEventListener("click", closeWorkspacePicker);
  $("wsMask").addEventListener("click", closeWorkspacePicker);
  $("wsConfirm").addEventListener("click", confirmWorkspace);
  $("wsUp").addEventListener("click", () => {
    if (state.wsDir && state.wsDir.parent) loadWorkspaceDir(state.wsDir.parent);
  });
  $("sendBtn").addEventListener("click", send);
  $("stopBtn").addEventListener("click", stop);
  $("settingsBtn").addEventListener("click", () => openDrawer("model"));
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
    if (e.key === "Escape") {
      closeDrawer();
      $("permMenu").classList.add("hidden");
    }
  });

  applyTheme(localStorage.getItem("wayfarer-theme") || "light");
  renderWorkspace();
  loadSessions();
  refreshModelBadge();
})();
