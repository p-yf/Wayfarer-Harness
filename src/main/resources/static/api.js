(function () {
  "use strict";

  async function request(method, url, body) {
    const options = { method: method, headers: {} };
    if (body !== undefined) {
      options.headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(body);
    }
    const response = await fetch(url, options);
    const text = await response.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch (_) { data = text; }
    }
    if (!response.ok) {
      const message = data && data.message ? data.message : ("请求失败 (" + response.status + ")");
      throw new Error(message);
    }
    return data;
  }

  async function streamAgent(payload, handlers, signal) {
    const response = await fetch("/api/agent/stream", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "text/event-stream" },
      body: JSON.stringify(payload),
      signal: signal
    });
    if (!response.ok) {
      let message = "请求失败 (" + response.status + ")";
      try {
        const data = await response.json();
        if (data && data.message) message = data.message;
      } catch (_) {}
      throw new Error(message);
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";
    let eventName = "message";
    let dataLines = [];

    function dispatch() {
      if (!dataLines.length) {
        eventName = "message";
        return;
      }
      const raw = dataLines.join("\n");
      let data = raw;
      try { data = JSON.parse(raw); } catch (_) {}
      if (handlers.onEvent) handlers.onEvent(eventName, data);
      eventName = "message";
      dataLines = [];
    }

    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop();
      for (const rawLine of lines) {
        const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
        if (line === "") {
          dispatch();
        } else if (line.startsWith("event:")) {
          eventName = line.slice(6).trim();
        } else if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).replace(/^ /, ""));
        }
      }
    }
    buffer += decoder.decode();
    if (buffer) {
      const line = buffer.endsWith("\r") ? buffer.slice(0, -1) : buffer;
      if (line.startsWith("event:")) eventName = line.slice(6).trim();
      else if (line.startsWith("data:")) dataLines.push(line.slice(5).replace(/^ /, ""));
    }
    dispatch();
    if (handlers.onDone) handlers.onDone();
  }

  window.Api = {
    listSessions: () => request("GET", "/api/sessions"),
    createSession: (name) => request("POST", "/api/sessions", { name: name }),
    deleteSession: (id) => request("DELETE", "/api/sessions/" + encodeURIComponent(id)),
    deleteWorkspace: (workspace) =>
      request("DELETE", "/api/sessions/workspace?workspace=" + encodeURIComponent(workspace)),
    sessionMessages: (id) => request("GET", "/api/sessions/" + encodeURIComponent(id) + "/messages"),
    sessionMeta: (id) => request("GET", "/api/sessions/" + encodeURIComponent(id)),
    updateSessionMetadata: (id, metadata) =>
      request("PUT", "/api/sessions/" + encodeURIComponent(id) + "/metadata", metadata),
    cloneSession: (id) => request("POST", "/api/sessions/" + encodeURIComponent(id) + "/clone"),
    forkSession: (id, traceId) =>
      request("POST", "/api/sessions/" + encodeURIComponent(id) + "/fork?traceId=" + encodeURIComponent(traceId)),
    resetSession: (id, traceId) =>
      request("POST", "/api/sessions/" + encodeURIComponent(id) + "/reset?traceId=" + encodeURIComponent(traceId)),
    stopAgent: (id) => request("POST", "/api/agent/stop/" + encodeURIComponent(id)),
    listWorkspace: (path) => request("GET", "/api/workspace/list" + (path ? "?path=" + encodeURIComponent(path) : "")),
    // 同一会话一次只返回最早的一条待确认项，提交后下一条才会出现
    hitlPending: (sessionId) => request("GET", "/api/hitl/pending" + (sessionId ? "?session=" + encodeURIComponent(sessionId) : "")),
    // decision 形如 {"sheet1": "key1"}，与待确认项 metadata 的页名、选项键一一对应
    // key 是「agentId/toolCallId」复合键，父子 Agent 的同名工具调用互不冲突
    hitlDecide: (key, decision) =>
      request("POST", "/api/hitl", Object.assign({ key }, decision)),
    listModelConfigs: () => request("GET", "/api/model-configs"),
    saveModelConfig: (name, config) => request("PUT", "/api/model-configs/" + encodeURIComponent(name), config),
    deleteModelConfig: (name) => request("DELETE", "/api/model-configs/" + encodeURIComponent(name)),
    listCompactConfigs: () => request("GET", "/api/memory-compact-configs"),
    saveCompactConfig: (name, config) => request("PUT", "/api/memory-compact-configs/" + encodeURIComponent(name), config),
    deleteCompactConfig: (name) => request("DELETE", "/api/memory-compact-configs/" + encodeURIComponent(name)),
    listPrompts: (agent) => request("GET", "/api/prompts?agent=" + encodeURIComponent(agent)),
    savePrompt: (agent, name, order, content) =>
      request("PUT", "/api/prompts/" + encodeURIComponent(agent) + "/" + encodeURIComponent(name) + "/" + order, { content: content }),
    deletePrompt: (agent, name, order) =>
      request("DELETE", "/api/prompts/" + encodeURIComponent(agent) + "/" + encodeURIComponent(name) + "/" + order),
    skillTree: (path) =>
      request("GET", "/api/skills/tree" + (path ? "?path=" + encodeURIComponent(path) : "")),
    skillRead: (path) => request("GET", "/api/skills/file?path=" + encodeURIComponent(path)),
    skillWrite: (path, content) => request("PUT", "/api/skills/file", { path: path, content: content }),
    skillMkdir: (path) => request("POST", "/api/skills/dir", { path: path }),
    skillDelete: (path) => request("DELETE", "/api/skills/entry?path=" + encodeURIComponent(path)),
    // 变更记录：sessionId/traceId 为空表示不按该维度过滤
    listChanges: (sessionId, traceId, limit) => {
      const query = [];
      if (sessionId) query.push("sessionId=" + encodeURIComponent(sessionId));
      if (traceId) query.push("traceId=" + encodeURIComponent(traceId));
      if (limit) query.push("limit=" + limit);
      return request("GET", "/api/changes" + (query.length ? "?" + query.join("&") : ""));
    },
    // 某个文件在一轮里的净变化：同一文件多次改动合并成一段补丁
    changeFileDiff: (sessionId, traceId, path) => {
      const query = ["path=" + encodeURIComponent(path)];
      if (sessionId) query.push("sessionId=" + encodeURIComponent(sessionId));
      if (traceId) query.push("traceId=" + encodeURIComponent(traceId));
      return request("GET", "/api/changes/file-diff?" + query.join("&"));
    },
    // RSI 沉淀仓库：kind ∈ skill|memory，agent 为沉淀主体（stores/<agent>/ 子树）
    rsiAgents: () => request("GET", "/api/rsi/agents"),
    rsiCommits: (kind, agent) =>
      request("GET", "/api/rsi/" + kind + "/commits?agent=" + encodeURIComponent(agent)),
    // 单提交 diff：{ commit }；任意两点：{ from, to }
    rsiDiff: (kind, agent, params) => {
      const query = ["agent=" + encodeURIComponent(agent)];
      if (params.commit) query.push("commit=" + encodeURIComponent(params.commit));
      if (params.from) query.push("from=" + encodeURIComponent(params.from));
      if (params.to) query.push("to=" + encodeURIComponent(params.to));
      return request("GET", "/api/rsi/" + kind + "/diff?" + query.join("&"));
    },
    rsiFiles: (kind, agent, commit) =>
      request("GET", "/api/rsi/" + kind + "/files?agent=" + encodeURIComponent(agent) +
        (commit ? "&commit=" + encodeURIComponent(commit) : "")),
    rsiFile: (kind, agent, path, commit) =>
      request("GET", "/api/rsi/" + kind + "/file?agent=" + encodeURIComponent(agent) +
        "&path=" + encodeURIComponent(path) + (commit ? "&commit=" + encodeURIComponent(commit) : "")),
    rsiReset: (kind, agent, commit) =>
      request("POST", "/api/rsi/" + kind + "/reset?agent=" + encodeURIComponent(agent) +
        "&commit=" + encodeURIComponent(commit)),
    streamAgent: streamAgent
  };
})();
