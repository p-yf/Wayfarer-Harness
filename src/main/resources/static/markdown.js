(function () {
  "use strict";

  function escapeHtml(text) {
    return String(text)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function inline(text) {
    const parts = escapeHtml(text).split(/`([^`\n]+)`/g);
    let out = "";
    for (let i = 0; i < parts.length; i++) {
      if (i % 2 === 1) {
        out += "<code>" + parts[i] + "</code>";
      } else {
        out += parts[i]
          .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
          .replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>")
          .replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
      }
    }
    return out;
  }

  /**
   * 拆分表格行，去掉首尾竖线带来的空单元格。
   */
  function splitRow(line) {
    let text = line.trim();
    if (text.startsWith("|")) text = text.slice(1);
    if (text.endsWith("|")) text = text.slice(0, -1);
    return text.split("|").map(function (cell) {
      return cell.trim();
    });
  }

  /**
   * 表格分隔行，如 | --- | :---: |。
   */
  function isTableDivider(line) {
    if (!line || line.indexOf("-") < 0 || line.indexOf("|") < 0) return false;
    const cells = splitRow(line);
    return cells.length > 0 && cells.every(function (cell) {
      return /^:?-{1,}:?$/.test(cell);
    });
  }

  function renderTable(rows) {
    const head = rows[0];
    const body = rows.slice(1);
    let html = '<div class="table-wrap"><table><thead><tr>';
    for (const cell of head) html += "<th>" + inline(cell) + "</th>";
    html += "</tr></thead><tbody>";
    for (const row of body) {
      html += "<tr>";
      for (let i = 0; i < head.length; i++) {
        html += "<td>" + inline(row[i] || "") + "</td>";
      }
      html += "</tr>";
    }
    return html + "</tbody></table></div>";
  }

  function render(src) {
    if (!src) return "";
    const lines = String(src).replace(/\r\n/g, "\n").split("\n");
    const html = [];
    let i = 0;
    let paragraph = [];
    let list = null;

    function flushParagraph() {
      if (paragraph.length) {
        html.push("<p>" + paragraph.map(inline).join("<br>") + "</p>");
        paragraph = [];
      }
    }
    function flushList() {
      if (list) {
        html.push("<" + list.tag + ">" + list.items.map(function (it) { return "<li>" + inline(it) + "</li>"; }).join("") + "</" + list.tag + ">");
        list = null;
      }
    }
    function flushAll() {
      flushParagraph();
      flushList();
    }

    while (i < lines.length) {
      const line = lines[i];

      // 代码围栏：语言标记后可跟任意说明文字
      const fence = line.match(/^```(.*)$/);
      if (fence) {
        flushAll();
        const lang = fence[1].trim().split(/\s+/)[0] || "";
        const buf = [];
        i++;
        while (i < lines.length && !/^```\s*$/.test(lines[i])) {
          buf.push(lines[i]);
          i++;
        }
        i++;
        html.push(
          '<div class="code-block"><div class="code-banner"><span>' + escapeHtml(lang || "text") +
          '</span><button type="button" class="code-copy">复制</button></div><pre><code>' +
          escapeHtml(buf.join("\n")) + "</code></pre></div>"
        );
        continue;
      }

      if (/^\s*$/.test(line)) {
        flushAll();
        i++;
        continue;
      }

      // 表格：当前行与下一行构成表头与分隔行
      if (line.indexOf("|") >= 0 && isTableDivider(lines[i + 1])) {
        flushAll();
        const rows = [splitRow(line)];
        i += 2;
        while (i < lines.length && lines[i].indexOf("|") >= 0 && !/^\s*$/.test(lines[i])) {
          rows.push(splitRow(lines[i]));
          i++;
        }
        html.push(renderTable(rows));
        continue;
      }

      // 分隔线要排在列表之前判断，避免与 -* 标记冲突
      if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
        flushAll();
        html.push("<hr>");
        i++;
        continue;
      }

      // 标题：井号后允许没有空格，兼容模型输出的紧凑写法
      const heading = line.match(/^(#{1,6})\s*(.+)$/);
      if (heading) {
        flushAll();
        const level = heading[1].length;
        html.push("<h" + level + ">" + inline(heading[2]) + "</h" + level + ">");
        i++;
        continue;
      }

      const quote = line.match(/^>\s?(.*)$/);
      if (quote) {
        flushAll();
        html.push("<blockquote>" + inline(quote[1]) + "</blockquote>");
        i++;
        continue;
      }

      // 列表：标记后允许没有空格；排除 **加粗**、--- 这类以标记字符开头的写法
      const ul = line.match(/^\s*[-*+](?![-*+\s])\s*(.+)$/) || line.match(/^\s*[-*+]\s+(.+)$/);
      const ol = line.match(/^\s*\d+[.)](?!\d)\s*(.+)$/);
      if (ul || ol) {
        flushParagraph();
        const tag = ul ? "ul" : "ol";
        if (!list || list.tag !== tag) {
          flushList();
          list = { tag: tag, items: [] };
        }
        list.items.push((ul || ol)[1]);
        i++;
        continue;
      }

      flushList();
      paragraph.push(line);
      i++;
    }
    flushAll();
    return html.join("");
  }

  window.Markdown = { render: render, escapeHtml: escapeHtml };
})();
