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
    // 右侧面板：一次只显示一个视图，changes 视图带当前轮次与数据
    panel: { open: false, view: "settings", traceId: null, entries: [], expanded: new Set(), patches: new Map() },
    // 已展开的变更气泡（按 traceId 记录），重渲染后保持展开状态
    changeBubbleOpen: new Set(),
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
      (state.views[sessionId] = {
        blocks: [],
        metadata: {},
        running: false,
        loading: null,
        abort: null,
        // traceId -> 聚合后的文件变更摘要；traceId -> 原始提交条目
        changeSummary: {},
        changeEntries: {}
      });
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

  /**
   * 会话按工作区归组展示：工作区相同的会话放在同一组，未设置工作区的会话单独成组并固定排最后。
   */
  function renderSessions() {
    const list = $("sessionList");
    if (!state.sessions.length) {
      list.innerHTML = '<div class="session-empty">还没有会话，点击上方「新会话」开始</div>';
      return;
    }
    list.innerHTML = "";
    for (const [workspace, metas] of groupByWorkspace()) {
      list.appendChild(workspaceGroup(workspace, metas));
    }
  }

  function workspaceOf(meta) {
    return String((meta.metadata && meta.metadata.workspace) || "");
  }

  function groupByWorkspace() {
    const groups = new Map();
    for (const meta of state.sessions) {
      const workspace = workspaceOf(meta);
      if (!groups.has(workspace)) groups.set(workspace, []);
      groups.get(workspace).push(meta);
    }
    return [...groups.entries()].sort((a, b) => {
      if (!a[0]) return 1;
      if (!b[0]) return -1;
      return folderName(a[0]).localeCompare(folderName(b[0]), "zh-CN");
    });
  }

  function workspaceGroup(workspace, metas) {
    const group = document.createElement("div");
    group.className = "ws-group";
    const head = document.createElement("div");
    head.className = "ws-group-head";
    head.title = workspace || "未设置工作区";
    head.innerHTML =
      '<svg viewBox="0 0 16 16" width="13" height="13"><path d="M1.8 4.2a1 1 0 0 1 1-1h3l1.3 1.4h5.1a1 1 0 0 1 1 1v6.2a1 1 0 0 1-1 1H2.8a1 1 0 0 1-1-1z" stroke="currentColor" stroke-width="1.3" fill="none" stroke-linejoin="round"/></svg>' +
      '<span class="ws-group-name">' + esc(workspace ? folderName(workspace) : "未设置工作区") + '</span>' +
      '<button class="ws-group-add" title="在该工作区新建会话"><svg viewBox="0 0 16 16" width="13" height="13"><path d="M8 3v10M3 8h10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg></button>' +
      '<button class="ws-group-del" title="删除工作区下的会话与变更记录"><svg viewBox="0 0 16 16" width="13" height="13"><path d="M3 4h10M6.5 4V2.8h3V4M4.5 4l.6 9h5.8l.6-9M6.8 6.5v4M9.2 6.5v4" stroke="currentColor" stroke-width="1.2" fill="none" stroke-linecap="round"/></svg></button>';
    head.querySelector(".ws-group-add").addEventListener("click", (e) => {
      e.stopPropagation();
      createSession(workspace);
    });
    head.querySelector(".ws-group-del").addEventListener("click", (e) => {
      e.stopPropagation();
      askDeleteWorkspace(head, workspace, metas);
    });
    group.appendChild(head);

    const body = document.createElement("div");
    body.className = "ws-group-body";
    for (const meta of metas) {
      body.appendChild(sessionItem(meta));
    }
    group.appendChild(body);
    return group;
  }

  /**
   * 删除工作区的确认直接展开在分组标题下，与单个会话删除的交互保持一致。
   */
  function askDeleteWorkspace(head, workspace, metas) {
    const group = head.parentElement;
    if (group.querySelector(".ws-confirm")) return;
    const box = document.createElement("div");
    box.className = "ws-confirm";
    box.innerHTML = '<span>删除该工作区下 ' + metas.length + ' 个会话？</span>' +
      '<button type="button" class="s-no">取消</button>' +
      '<button type="button" class="s-yes">删除</button>';
    head.after(box);
    box.addEventListener("click", (e) => e.stopPropagation());
    box.querySelector(".s-no").addEventListener("click", () => box.remove());
    box.querySelector(".s-yes").addEventListener("click", async () => {
      const yes = box.querySelector(".s-yes");
      yes.disabled = true;
      try {
        for (const meta of metas) {
          await cancelRun(meta.id);
        }
        const removedCurrent = metas.some((meta) => meta.id === state.currentId);
        for (const meta of metas) {
          delete state.views[meta.id];
        }
        const result = await Api.deleteWorkspace(workspace);
        if (removedCurrent) {
          state.currentId = null;
          applyViewMeta({});
          renderHitl(state.hitlPending);
          renderChat();
          syncComposer();
        }
        await loadSessions();
        toast("已删除 " + result.sessions + " 个会话" + (result.repositoryDeleted ? "及变更记录" : ""));
      } catch (err) {
        toast(err.message, true);
        yes.disabled = false;
      }
    });
  }

  function sessionItem(meta) {
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
    return item;
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

  /**
   * 新建会话。指定 workspace 时直接把新会话归到该工作区下，否则沿用待写入的工作区设置。
   */
  async function createSession(workspace) {
    try {
      if (typeof workspace === "string" && workspace) state.pendingWorkspace = workspace;
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
    // 变更面板属于上一个会话的轮次，切换会话时收起，避免展示错数据
    if (state.panel.view === "changes") closePanel();
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
      const [meta, messages, changes] = await Promise.all([
        Api.sessionMeta(id),
        Api.sessionMessages(id),
        // 变更记录是历史渲染的一部分：拿不到时按无变更处理，不影响对话
        Api.listChanges(id, null, 200).catch(() => [])
      ]);
      const view = viewOf(id);
      // 历史还没返回就开跑了，以实时视图为准，避免覆盖本轮输出
      if (view.running) return;
      applyChangeEntries(view, changes);
      view.blocks = historyToBlocks(messages, view.changeSummary);
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
        toast("已从该轮分支出新会话");
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

  function historyToBlocks(messages, changeSummary) {
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
    appendChangeBlocks(blocks, changeSummary);
    return blocks;
  }

  /**
   * 历史渲染时把变更气泡插到每轮最后一个块之后。
   * 先算好插入位置再倒序插入，避免前面的插入导致后面下标错位。
   */
  function appendChangeBlocks(blocks, changeSummary) {
    const insertions = [];
    for (const [traceId, summary] of Object.entries(changeSummary || {})) {
      if (!summary || !summary.files.length) continue;
      let last = -1;
      blocks.forEach((block, index) => {
        if (block.traceId === traceId) last = index;
      });
      if (last >= 0) {
        insertions.push({ index: last + 1, block: { kind: "change", traceId, summary } });
      }
    }
    insertions
      .sort((left, right) => right.index - left.index)
      .forEach((item) => blocks.splice(item.index, 0, item.block));
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
            '<button type="button" data-act="fork" title="从此分支：保留到这一轮为止的消息，另开一个会话" aria-label="从此分支">' + forkIcon + "</button>" +
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
    } else if (block.kind === "change") {
      // 本轮文件变更气泡：主体点击就地展开文件清单，右侧箭头打开详情面板
      el.className = "change-hold";
      el.innerHTML = '<div class="change-bubble">' + changeBubbleHtml(block.summary, block.traceId) + "</div>";
      const bubble = el.querySelector(".change-bubble");
      if (state.changeBubbleOpen.has(block.traceId)) {
        bubble.classList.add("open");
      }
      bubble.querySelector(".change-bubble-main").addEventListener("click", () => {
        const open = bubble.classList.toggle("open");
        if (open) state.changeBubbleOpen.add(block.traceId);
        else state.changeBubbleOpen.delete(block.traceId);
      });
      bubble.querySelectorAll(".change-bubble-file").forEach((row) => {
        row.addEventListener("click", () => openChangeFile(block.traceId, row.dataset.path));
      });
      bubble.querySelector(".change-bubble-open").addEventListener("click", () => openChangePanel(block.traceId));
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
      // 内部事件：压缩提示、本轮整体变更、变更记录失败提示
      if (data && typeof data === "object" && data.type === "compact") {
        pushBlock(view, { kind: "compact" });
      } else if (data && typeof data === "object" && data.type === "changes") {
        applyChangesEvent(view, data);
      } else if (data && typeof data === "object" && data.type === "change-error") {
        toast(data.message || "变更记录失败", true);
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

  const PANEL_TITLES = { settings: "设置", prompt: "系统提示词", skills: "Skills", changes: "本轮变更" };

  /**
   * 打开右侧面板。view 决定内容，payload 携带 changes 视图需要的 traceId 与条目。
   */
  function openPanel(view, payload) {
    state.panel.view = view || "settings";
    if (payload && payload.traceId) state.panel.traceId = payload.traceId;
    if (payload && payload.entries) state.panel.entries = payload.entries;
    state.editing = null;
    state.promptEditing = null;
    resetSkillBrowser();
    $("panelTitle").textContent = PANEL_TITLES[state.panel.view] || "设置";
    $("panelTabs").classList.toggle("hidden", state.panel.view !== "settings");
    document.querySelectorAll("#panelTabs .tab").forEach((item) => {
      item.classList.toggle("active", item.dataset.tab === state.tab);
    });
    setPanelOpen(true);
    loadPanel();
  }

  function closePanel() {
    setPanelOpen(false);
  }

  /**
   * 顶栏设置按钮：已展开设置则收起；停在其它视图则切回设置；收起时直接展开设置。
   */
  function toggleSettingsPanel() {
    if (state.panel.open && state.panel.view === "settings") {
      closePanel();
      return;
    }
    openPanel("settings");
  }

  function resetSkillBrowser() {
    state.skillPath = "";
    state.skillParent = null;
    state.skillEntries = [];
    state.skillFile = null;
    state.skillCreating = null;
  }

  /**
   * 按当前面板视图加载数据，加载完统一交给 renderPanel 分发。
   */
  async function loadPanel() {
    try {
      if (state.panel.view === "settings") {
        if (state.tab === "model") state.modelConfigs = await Api.listModelConfigs();
        else state.compactConfigs = await Api.listCompactConfigs();
      } else if (state.panel.view === "prompt") {
        state.prompts = await Api.listPrompts(AGENT_NAME);
      } else if (state.panel.view === "skills") {
        const tree = await Api.skillTree(state.skillPath);
        state.skillPath = tree.path || "";
        state.skillParent = tree.parent;
        state.skillEntries = tree.entries || [];
      } else if (state.panel.view === "changes" && !state.panel.entries.length) {
        state.panel.entries = await Api.listChanges(state.currentId, state.panel.traceId, 200);
      }
      renderPanel();
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

  /**
   * 面板渲染分发：设置页有自己的页签，其余视图各自渲染。
   */
  function renderPanel() {
    if (state.panel.view === "prompt") {
      renderPrompts();
      return;
    }
    if (state.panel.view === "skills") {
      renderSkills();
      return;
    }
    if (state.panel.view === "changes") {
      renderChanges();
      return;
    }
    renderSettings($("panelBody"));
  }

  /**
   * 设置页：模型配置与记忆压缩共用一套表单结构，按 state.tab 区分。
   */
  function renderSettings(body) {
    const isModel = state.tab === "model";
    const configs = isModel ? state.modelConfigs : state.compactConfigs;
    const names = Object.keys(configs || {}).sort();
    const help = isModel
      ? "配置以 JSONL 文件保存在 modelconfig 目录，运行时读取第一个「已启用」的配置。"
      : "配置以 JSONL 文件保存在 memorycompactconfig 目录，运行时读取第一个「已启用」的配置，全部未启用时使用内置默认值。触发条件是估算 token 超过「上下文窗口 × 触发比例」，默认 128000 × 0.7，想验证效果可把窗口调小，例如 2000。";
    let html =
      '<div class="cfg-toolbar"><span class="cfg-note" style="margin:0">共 ' + names.length +
      ' 个配置<span class="help-tip" title="' + esc(help) + '">?</span></span>' +
      '<button class="cfg-new" id="cfgNew">＋ 新建配置</button></div>';

    if (!names.length) html += '<div class="panel-empty">暂无配置，点击「新建配置」创建</div>';
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
        renderSettings(body);
      });
      item.querySelector(".cfg-del").addEventListener("click", async (e) => {
        e.stopPropagation();
        if (!confirm("删除配置「" + name + "」？")) return;
        try {
          if (isModel) await Api.deleteModelConfig(name);
          else await Api.deleteCompactConfig(name);
          if (state.editing === name) state.editing = null;
          toast("已删除");
          await loadPanel();
          refreshModelBadge();
        } catch (err) {
          toast(err.message, true);
        }
      });
      body.appendChild(item);
    }

    $("cfgNew").addEventListener("click", () => {
      state.editing = "__new__";
      renderSettings(body);
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
      renderSettings(body);
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
        await loadPanel();
        refreshModelBadge();
      } catch (e) {
        toast(e.message, true);
      }
    });
  }

  function renderPrompts() {
    const body = $("panelBody");
    const prompts = state.prompts || [];
    const help = "保存在 systemprompt 目录，按顺序拼接后作为模型的首条 system 消息。当前 agent：" + AGENT_NAME;
    let html =
      '<div class="cfg-note">系统提示词以「agent名称-提示词名称-顺序.md」<span class="help-tip" title="' + esc(help) + '">?</span></div>' +
      '<div class="cfg-toolbar"><span class="cfg-note" style="margin:0">共 ' + prompts.length + " 个提示词</span>" +
      '<button class="cfg-new" id="promptNew">＋ 新建提示词</button></div>';
    if (!prompts.length) html += '<div class="panel-empty">暂无提示词，点击「新建提示词」创建</div>';
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
          await loadPanel();
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
        await loadPanel();
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
    await loadPanel();
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
    const body = $("panelBody");
    const entries = state.skillEntries || [];
    const crumbs = state.skillPath ? state.skillPath.split("/") : [];
    const help = "以文件系统方式浏览 skills 目录：点击文件夹进入，点击文件编辑。只有包含 SKILL.md 的技能文件夹会被注入系统提示词。";
    let html =
      '<div class="cfg-note">skills 目录<span class="help-tip" title="' + esc(help) + '">?</span></div>' +
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
      empty.className = "panel-empty";
      empty.textContent = "skills 目录还是空的，先新建文件夹";
      list.appendChild(empty);
    } else if (!entries.length && state.skillPath) {
      const empty = document.createElement("div");
      empty.className = "panel-empty";
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
          await loadPanel();
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
        await loadPanel();
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

  /* ---------------- 三栏布局：折叠、拖拽与持久化 ---------------- */

  const LAYOUT = {
    sidebar: { min: 200, max: 420, fallback: 264, key: "wayfarer-sidebar-width" },
    panel: { min: 320, max: 720, fallback: 460, key: "wayfarer-panel-width" }
  };

  function isMobile() {
    return window.innerWidth <= 900;
  }

  function storedWidth(key, fallback) {
    const value = Number(localStorage.getItem(key));
    return Number.isFinite(value) && value > 0 ? value : fallback;
  }

  /** 宽度写进 CSS 变量，两个栏位共用同一套夹取逻辑 */
  function applyWidth(kind, width) {
    const spec = LAYOUT[kind];
    const clamped = Math.max(spec.min, Math.min(spec.max, Math.round(width)));
    document.documentElement.style.setProperty("--" + kind + "-width", clamped + "px");
    return clamped;
  }

  function setSidebarCollapsed(collapsed) {
    $("sidebar").classList.toggle("collapsed", collapsed);
    $("sidebarResizer").classList.toggle("hidden", collapsed);
    $("sidebarToggle").setAttribute("aria-expanded", String(!collapsed));
    localStorage.setItem("wayfarer-sidebar-collapsed", collapsed ? "1" : "0");
  }

  function setPanelOpen(open) {
    state.panel.open = open;
    $("panel").classList.toggle("collapsed", !open);
    $("panelResizer").classList.toggle("hidden", !open);
    // 桌面端面板在文档流内，不需要遮罩；移动端才用遮罩
    $("panelMask").classList.toggle("hidden", !open || !isMobile());
    $("settingsBtn").setAttribute("aria-expanded", String(open));
    localStorage.setItem("wayfarer-panel-collapsed", open ? "0" : "1");
  }

  function toggleSidebar() {
    if (isMobile()) {
      $("sidebar").classList.toggle("open");
      return;
    }
    setSidebarCollapsed(!$("sidebar").classList.contains("collapsed"));
  }

  /**
   * 拖拽调宽：edge 表示手柄位于哪一侧。
   * 左边界用指针 X 减去容器左边距；右边界用容器右边距减去指针 X。
   */
  function bindResizer(id, kind, edge) {
    const handle = $(id);
    handle.addEventListener("pointerdown", (event) => {
      if (isMobile()) return;
      event.preventDefault();
      handle.setPointerCapture(event.pointerId);
      document.body.classList.add("is-resizing");
      let width = 0;
      const onMove = (moveEvent) => {
        const rect = $("shell").getBoundingClientRect();
        width = applyWidth(kind, edge === "left" ? moveEvent.clientX - rect.left : rect.right - moveEvent.clientX);
      };
      const onUp = (upEvent) => {
        handle.removeEventListener("pointermove", onMove);
        handle.removeEventListener("pointerup", onUp);
        document.body.classList.remove("is-resizing");
        if (handle.hasPointerCapture(upEvent.pointerId)) handle.releasePointerCapture(upEvent.pointerId);
        if (width) localStorage.setItem(LAYOUT[kind].key, String(width));
      };
      handle.addEventListener("pointermove", onMove);
      handle.addEventListener("pointerup", onUp);
    });
  }

  function initLayout() {
    const panelFallback = Math.min(520, Math.max(360, Math.round(window.innerWidth * 0.34)));
    applyWidth("sidebar", storedWidth(LAYOUT.sidebar.key, LAYOUT.sidebar.fallback));
    applyWidth("panel", storedWidth(LAYOUT.panel.key, panelFallback));
    setSidebarCollapsed(localStorage.getItem("wayfarer-sidebar-collapsed") === "1");
    bindResizer("sidebarResizer", "sidebar", "left");
    bindResizer("panelResizer", "panel", "right");

    // 面板上次是展开的，恢复后直接加载设置内容
    if (localStorage.getItem("wayfarer-panel-collapsed") === "0") openPanel("settings");
    else setPanelOpen(false);

    window.addEventListener("resize", () => {
      $("panelMask").classList.toggle("hidden", !state.panel.open || !isMobile());
    });
  }

  /* ---------------- 文件变更：摘要、气泡与 diff ---------------- */

  const CHANGE_TYPE_LABELS = { ADD: "新增", MODIFY: "修改", DELETE: "删除", RENAME: "重命名", COPY: "复制" };

  function changeTypeLabel(type) {
    return CHANGE_TYPE_LABELS[type] || (type ? String(type).toLowerCase() : "变更");
  }

  function baseName(path) {
    const parts = String(path || "").split("/");
    return parts[parts.length - 1] || path;
  }

  function isDarkTheme() {
    return document.body.hasAttribute("data-ds-dark-theme");
  }

  /** 把一组提交条目聚合成气泡展示需要的形状：调用次数、按路径去重的文件、增删合计 */
  function summarizeChanges(entries) {
    const summary = { commits: 0, files: [], insertions: 0, deletions: 0 };
    const byPath = {};
    for (const entry of entries || []) {
      summary.commits++;
      for (const file of entry.files || []) {
        const item = byPath[file.path];
        if (item) {
          item.insertions += file.insertions || 0;
          item.deletions += file.deletions || 0;
          item.changeType = file.changeType || item.changeType;
        } else {
          const created = {
            path: file.path,
            changeType: file.changeType,
            insertions: file.insertions || 0,
            deletions: file.deletions || 0,
            binary: !!file.binary
          };
          byPath[file.path] = created;
          summary.files.push(created);
        }
        summary.insertions += file.insertions || 0;
        summary.deletions += file.deletions || 0;
      }
    }
    return summary;
  }

  /** 历史渲染前按 traceId 归档，供气泡与面板共用 */
  function applyChangeEntries(view, entries) {
    view.changeEntries = {};
    view.changeSummary = {};
    for (const entry of entries || []) {
      if (!entry.traceId) continue;
      (view.changeEntries[entry.traceId] = view.changeEntries[entry.traceId] || []).push(entry);
    }
    for (const [traceId, list] of Object.entries(view.changeEntries)) {
      view.changeSummary[traceId] = summarizeChanges(list);
    }
  }

  /** 轮次结束推送：更新本轮摘要，已有气泡就地替换，否则追加 */
  function applyChangesEvent(view, data) {
    const traceId = data.traceId;
    const entries = data.entries || [];
    if (!traceId || !entries.length) return;
    view.changeEntries[traceId] = entries;
    view.changeSummary[traceId] = summarizeChanges(entries);
    const existing = view.blocks.find((block) => block.kind === "change" && block.traceId === traceId);
    if (existing) {
      existing.summary = view.changeSummary[traceId];
      swapBlock(view, existing);
    } else {
      pushBlock(view, { kind: "change", traceId, summary: view.changeSummary[traceId] });
    }
  }

  /** 增删行数片段：+ 绿色、− 红色，二进制文件单独标注 */
  function changeStatHtml(file) {
    if (file.binary) return '<span class="change-bubble-filestat">二进制</span>';
    return '<span class="change-bubble-filestat">' +
      '<span class="change-add">+' + file.insertions + "</span> " +
      '<span class="change-del">−' + file.deletions + "</span></span>";
  }

  /** 总览气泡：左侧文件图标 + 文件数，右侧增删行数，竖线后的箭头打开详情面板 */
  function changeBubbleHtml(summary, traceId) {
    const files = summary.files || [];
    const rows = files.map((file) =>
      '<button type="button" class="change-bubble-file" data-path="' + esc(file.path) + '" ' +
        'data-trace="' + esc(traceId || "") + '">' +
        '<span class="change-bubble-name">' + esc(baseName(file.path)) + "</span>" +
        '<span class="change-bubble-path" title="' + esc(file.path) + '">' + esc(file.path) + "</span>" +
        changeStatHtml(file) +
      "</button>").join("");
    return '<div class="change-bubble-head">' +
      '<button type="button" class="change-bubble-main">' +
        '<span class="change-bubble-icon">' +
          '<svg viewBox="0 0 16 16" width="15" height="15">' +
            '<path d="M4.2 1.8h4.6l2.9 2.9v8.5a1 1 0 0 1-1 1H4.2a1 1 0 0 1-1-1V2.8a1 1 0 0 1 1-1z" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linejoin="round"/>' +
            '<path d="M8.7 2v3h3" stroke="currentColor" stroke-width="1.4" fill="none" stroke-linejoin="round"/>' +
          "</svg>" +
        "</span>" +
        '<span class="change-bubble-title">' + files.length + " 个文件已更改</span>" +
        '<span class="change-bubble-stat"><span class="change-add">+' + summary.insertions + "</span> " +
          '<span class="change-del">−' + summary.deletions + "</span></span>" +
      "</button>" +
      '<button type="button" class="change-bubble-open" title="在右侧面板查看详情">' +
        '<svg viewBox="0 0 16 16" width="16" height="16">' +
          '<path d="M4.8 11.2l6.4-6.4M6.2 4.8h5v5" stroke="currentColor" stroke-width="1.9" fill="none" stroke-linecap="round" stroke-linejoin="round"/>' +
        "</svg>" +
      "</button>" +
      "</div>" +
      '<div class="change-bubble-list">' + rows + "</div>";
  }

  function openChangePanel(traceId) {
    const view = currentView();
    state.panel.entries = (view && view.changeEntries[traceId]) || [];
    state.panel.expanded = new Set();
    state.panel.patches = new Map();
    openPanel("changes", { traceId, entries: state.panel.entries });
  }

  /** 点击气泡里的文件行：打开右侧面板，展开该文件并滚动到它的位置 */
  function openChangeFile(traceId, path) {
    const view = currentView();
    const entries = (view && view.changeEntries[traceId]) || [];
    const entry = entries.find((item) => (item.files || []).some((file) => file.path === path));
    openChangePanel(traceId);
    if (!entry) return;
    const key = entry.commitId + ":" + path;
    if (!state.panel.expanded.has(key)) {
      toggleChangeDiff(entry.commitId, path);
    }
    const holder = findChangeFile(key);
    if (holder) holder.scrollIntoView({ block: "start" });
  }

  function fileStat(file) {
    return file.binary ? "二进制" : "+" + file.insertions + " −" + file.deletions;
  }

  function findChangeFile(key) {
    return Array.from($("panelBody").querySelectorAll(".change-file"))
      .find((item) => item.dataset.key === key);
  }

  function renderChanges() {
    const body = $("panelBody");
    const entries = state.panel.entries || [];
    if (!entries.length) {
      body.innerHTML = '<div class="panel-empty">本轮没有文件变更</div>';
      return;
    }

    const summary = summarizeChanges(entries);
    let html = '<div class="change-summary">' + summary.commits + " 次调用 · " +
      summary.files.length + " 个文件 · +" + summary.insertions + " −" + summary.deletions + "</div>";

    for (const entry of entries) {
      const stat = summarizeChanges([entry]);
      html += '<div class="change-group"><div class="change-group-head">' +
        '<span class="change-tool">' + esc(entry.tool || "tool") + "</span>" +
        '<span class="change-group-title" title="' + esc(entry.summary || "") + '">' + esc(entry.summary || "") + "</span>" +
        '<span class="change-group-stat">+' + stat.insertions + " −" + stat.deletions + "</span>" +
        "</div>";
      for (const file of entry.files || []) {
        const key = entry.commitId + ":" + file.path;
        html += '<div class="change-file" data-key="' + esc(key) + '">' +
          '<button type="button" class="change-file-row" data-commit="' + esc(entry.commitId) +
          '" data-path="' + esc(file.path) + '">' +
          '<span class="change-file-path" title="' + esc(file.path) + '">' + esc(file.path) + "</span>" +
          '<span class="change-badge ' + esc(file.changeType || "") + '">' + esc(changeTypeLabel(file.changeType)) + "</span>" +
          '<span class="change-file-stat">' + esc(fileStat(file)) + "</span>" +
          '<span class="change-bubble-arrow">' + (state.panel.expanded.has(key) ? "▾" : "▸") + "</span>" +
          "</button>" +
          '<div class="change-diff hidden"></div>' +
          "</div>";
      }
      html += "</div>";
    }
    body.innerHTML = html;

    body.querySelectorAll(".change-file-row").forEach((row) => {
      row.addEventListener("click", () => toggleChangeDiff(row.dataset.commit, row.dataset.path));
    });

    // 主题切换等场景会重建面板：把已展开的文件恢复展开
    for (const key of state.panel.expanded) {
      const holder = findChangeFile(key);
      if (!holder) continue;
      const diff = holder.querySelector(".change-diff");
      const arrow = holder.querySelector(".change-bubble-arrow");
      diff.classList.remove("hidden");
      if (arrow) arrow.textContent = "▾";
      if (state.panel.patches.has(key)) {
        renderDiff(diff, state.panel.patches.get(key));
      } else {
        const separator = key.indexOf(":");
        loadDiff(key, key.slice(0, separator), key.slice(separator + 1), diff);
      }
    }
  }

  function toggleChangeDiff(commitId, path) {
    const key = commitId + ":" + path;
    const holder = findChangeFile(key);
    if (!holder) return;
    const diff = holder.querySelector(".change-diff");
    const arrow = holder.querySelector(".change-bubble-arrow");

    if (state.panel.expanded.has(key)) {
      state.panel.expanded.delete(key);
      diff.classList.add("hidden");
      diff.innerHTML = "";
      if (arrow) arrow.textContent = "▸";
      return;
    }

    state.panel.expanded.add(key);
    diff.classList.remove("hidden");
    if (arrow) arrow.textContent = "▾";
    if (state.panel.patches.has(key)) {
      renderDiff(diff, state.panel.patches.get(key));
    } else {
      loadDiff(key, commitId, path, diff);
    }
  }

  /** 按需拉取某个文件的 unified diff，结果缓存避免重复请求 */
  async function loadDiff(key, commitId, path, container) {
    container.innerHTML = '<div class="change-note">加载中…</div>';
    try {
      const result = await Api.changeDiff(commitId, path);
      const patch = (result && result.patch) || "";
      state.panel.patches.set(key, patch);
      renderDiff(container, patch);
    } catch (e) {
      container.innerHTML = '<div class="change-note">' + esc(e.message) + "</div>";
    }
  }

  /** diff2html 渲染；依赖缺失或解析失败时退回纯文本 */
  function renderDiff(container, patch) {
    if (!patch || !patch.trim()) {
      container.innerHTML = '<div class="change-note">无文本差异</div>';
      return;
    }
    let text = patch;
    let note = "";
    const lines = text.split("\n");
    if (text.length > 200 * 1024 || lines.length > 3000) {
      text = lines.slice(0, 2000).join("\n");
      note = '<div class="change-note">补丁过长，仅展示前 2000 行</div>';
    }
    if (window.Diff2Html) {
      try {
        container.innerHTML = window.Diff2Html.html(text, {
          drawFileList: false,
          matching: "lines",
          outputFormat: "line-by-line",
          colorScheme: isDarkTheme() ? "dark" : "light"
        }) + note;
        return;
      } catch (_) {
        // 解析失败时走下面的纯文本降级
      }
    }
    container.innerHTML = '<pre class="change-raw">' + esc(text) + "</pre>" + note;
  }

  $("newChatBtn").addEventListener("click", createSession);
  $("promptNavBtn").addEventListener("click", () => openPanel("prompt"));
  $("skillsNavBtn").addEventListener("click", () => openPanel("skills"));
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
  $("settingsBtn").addEventListener("click", toggleSettingsPanel);
  $("panelClose").addEventListener("click", closePanel);
  $("panelMask").addEventListener("click", closePanel);
  $("sidebarToggle").addEventListener("click", toggleSidebar);
  $("themeBtn").addEventListener("click", () => {
    applyTheme(document.body.hasAttribute("data-ds-dark-theme") ? "light" : "dark");
    // diff2html 的配色跟随主题，已展开的变更面板需要按新主题重绘
    if (state.panel.open && state.panel.view === "changes") renderChanges();
  });

  document.querySelectorAll("#panelTabs .tab").forEach((tab) => {
    tab.addEventListener("click", () => {
      document.querySelectorAll("#panelTabs .tab").forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      state.tab = tab.dataset.tab;
      state.editing = null;
      state.promptEditing = null;
      loadPanel();
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
      closePanel();
      $("permMenu").classList.add("hidden");
    }
  });

  applyTheme(localStorage.getItem("wayfarer-theme") || "light");
  initLayout();
  renderWorkspace();
  loadSessions();
  refreshModelBadge();
})();
