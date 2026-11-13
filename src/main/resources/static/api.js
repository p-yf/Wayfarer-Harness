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
    hitlPending: () => request("GET", "/api/hitl/pending"),
    hitlDecide: (toolCallId, approved) => request("POST", "/api/hitl", { toolCallId: toolCallId, approved: approved }),
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
    streamAgent: streamAgent
  };
})();
