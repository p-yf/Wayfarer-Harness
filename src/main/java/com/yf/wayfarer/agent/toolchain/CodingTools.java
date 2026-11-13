package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.toolcall.Tool;
import com.yf.wayfarer.toolcall.ToolParam;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 编码工具。
 *
 * @author yyf
 * @description 提供 read、bash、edit、write 四个文件与命令工具，相对路径基于上下文中的工作区解析
 */
public class CodingTools {

    private static final int MAX_LINES = 2000;
    private static final int MAX_BYTES = 50 * 1024;

    @Tool(name = "read", description = "读取文本文件内容，输出超过 2000 行或 50KB 时截断；大文件用 offset/limit 分段读取")
    public String read(AgentContext context,
                       @ToolParam(description = "文件路径，相对路径基于工作区") String path,
                       @ToolParam(description = "起始行号，从 1 开始，可选") Integer offset,
                       @ToolParam(description = "最多读取的行数，可选") Integer limit) {
        if (path == null || path.isBlank()) {
            return "错误：缺少参数 path";
        }
        Path file = resolve(context, path);
        try {
            if (!Files.isRegularFile(file)) {
                return "错误：文件不存在或不是普通文件：" + file;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String[] lines = splitLines(text);
            int startLine = offset == null || offset <= 0 ? 0 : Math.min(offset - 1, lines.length);
            if (startLine >= lines.length) {
                return "错误：起始行 " + offset + " 超出文件范围（共 " + lines.length + " 行）";
            }
            int startDisplay = startLine + 1;
            int endLine = limit == null || limit <= 0
                    ? lines.length
                    : Math.min(startLine + limit, lines.length);
            String selected = String.join("\n", slice(lines, startLine, endLine));

            Truncation truncation = truncateHead(selected);
            if (truncation.firstLineExceedsLimit()) {
                return "[第 " + startDisplay + " 行超过 " + MAX_BYTES / 1024 + "KB]，请改用 bash 分段查看";
            }
            String output = truncation.content();
            if (truncation.truncated()) {
                int endDisplay = startDisplay + truncation.outputLines() - 1;
                output += "\n\n[已显示第 " + startDisplay + "-" + endDisplay + " 行，共 " + lines.length
                        + " 行。使用 offset=" + (endDisplay + 1) + " 继续读取。]";
            } else if (endLine < lines.length) {
                output += "\n\n[文件还有 " + (lines.length - endLine) + " 行，使用 offset="
                        + (endLine + 1) + " 继续读取。]";
            }
            return output;
        } catch (IOException e) {
            return "错误：读取文件失败：" + e.getMessage();
        }
    }

    @Tool(name = "bash", description = "在当前工作区执行 shell 命令，返回合并后的标准输出与错误输出，输出超过限制时截断尾部")
    public String bash(AgentContext context,
                       @ToolParam(description = "要执行的 shell 命令") String command,
                       @ToolParam(description = "超时秒数，可选，不传则不限制") Integer timeout) {
        if (command == null || command.isBlank()) {
            return "错误：缺少参数 command";
        }
        Path cwd = workspace(context);
        try {
            if (!Files.isDirectory(cwd)) {
                return "错误：工作目录不存在：" + cwd;
            }
            List<String> cmd = new ArrayList<>();
            if (isWindows()) {
                cmd.add("cmd.exe");
                cmd.add("/d");
                cmd.add("/s");
                cmd.add("/c");
                // 切到 utf-8 代码页，否则中文系统的报错文本会按 GBK 输出而被按 utf-8 解码成乱码
                cmd.add("chcp 65001>nul & " + command);
            } else {
                cmd.add("/bin/bash");
                cmd.add("-lc");
                cmd.add(command);
            }
            Process process = new ProcessBuilder(cmd)
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            // 输出在独立线程读取，避免进程缓冲区写满后阻塞 waitFor
            CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
                try (InputStream in = process.getInputStream()) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });

            boolean finished;
            if (timeout != null && timeout > 0) {
                finished = process.waitFor(timeout, TimeUnit.SECONDS);
            } else {
                process.waitFor();
                finished = true;
            }
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            String text = output.get(10, TimeUnit.SECONDS);
            if (text.isEmpty()) {
                text = "(无输出)";
            }
            Truncation truncation = truncateTail(text);
            String content = truncation.content();
            if (truncation.truncated()) {
                content += "\n\n[仅显示最后 " + truncation.outputLines() + " 行，共 "
                        + truncation.totalLines() + " 行]";
            }
            if (!finished) {
                return append(content, "命令超时，已在 " + timeout + " 秒后终止");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return append(content, "命令退出码 " + exitCode);
            }
            return content;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "错误：命令执行被中断";
        } catch (Exception e) {
            return "错误：命令执行失败：" + e.getMessage();
        }
    }

    @Tool(name = "edit", description = "对文件做精确文本替换，oldText 必须与原文件完全一致且在文件中唯一")
    public String edit(AgentContext context,
                       @ToolParam(description = "文件路径，相对路径基于工作区") String path,
                       @ToolParam(description = "要被替换的原文，必须唯一匹配") String oldText,
                       @ToolParam(description = "替换后的新文本") String newText) {
        if (path == null || path.isBlank()) {
            return "错误：缺少参数 path";
        }
        if (oldText == null || oldText.isEmpty()) {
            return "错误：oldText 不能为空";
        }
        Path file = resolve(context, path);
        try {
            if (!Files.isRegularFile(file)) {
                return "错误：文件不存在或不是普通文件：" + file;
            }
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            String bom = raw.startsWith("\uFEFF") ? "\uFEFF" : "";
            String content = bom.isEmpty() ? raw : raw.substring(1);
            String ending = detectLineEnding(content);
            String normalized = normalizeToLf(content);
            String target = normalizeToLf(oldText);
            int occurrences = countOccurrences(normalized, target);
            if (occurrences == 0) {
                return "错误：未在 " + path + " 中找到 oldText，请先读取文件确认原文";
            }
            if (occurrences > 1) {
                return "错误：oldText 在 " + path + " 中出现 " + occurrences + " 次，请提供更长的唯一片段";
            }
            String updated = normalized.replace(target, normalizeToLf(newText == null ? "" : newText));
            Files.writeString(file, bom + restoreLineEndings(updated, ending), StandardCharsets.UTF_8);
            return "已替换 " + path + " 中的 1 处内容";
        } catch (IOException e) {
            return "错误：编辑文件失败：" + e.getMessage();
        }
    }

    @Tool(name = "write", description = "写入文件内容，文件不存在时创建、存在时覆盖，并自动创建父目录")
    public String write(AgentContext context,
                        @ToolParam(description = "文件路径，相对路径基于工作区") String path,
                        @ToolParam(description = "要写入的完整内容") String content) {
        if (path == null || path.isBlank()) {
            return "错误：缺少参数 path";
        }
        Path file = resolve(context, path);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            return "已写入 " + path;
        } catch (IOException e) {
            return "错误：写入文件失败：" + e.getMessage();
        }
    }

    /**
     * 相对路径基于上下文中的工作区解析，绝对路径直接使用。
     */
    private static Path resolve(AgentContext context, String path) {
        Path target = Path.of(path);
        if (target.isAbsolute()) {
            return target.normalize();
        }
        return workspace(context).resolve(path).normalize();
    }

    private static Path workspace(AgentContext context) {
        String workspace = context == null ? null : context.get(AgentContext.WORKSPACE, String.class);
        if (workspace == null || workspace.isBlank()) {
            return Path.of(System.getProperty("user.dir"));
        }
        return Path.of(workspace).toAbsolutePath().normalize();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String append(String content, String status) {
        return content.isEmpty() ? status : content + "\n\n" + status;
    }

    private static String[] splitLines(String text) {
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        if (text.endsWith("\n") && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines.toArray(String[]::new);
    }

    private static List<String> slice(String[] lines, int start, int end) {
        List<String> selected = new ArrayList<>();
        for (int i = start; i < end; i++) {
            selected.add(lines[i]);
        }
        return selected;
    }

    private static String detectLineEnding(String content) {
        int crlf = content.indexOf("\r\n");
        int lf = content.indexOf('\n');
        if (lf < 0 || crlf < 0) {
            return "\n";
        }
        return crlf < lf ? "\r\n" : "\n";
    }

    private static String normalizeToLf(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String restoreLineEndings(String text, String ending) {
        return "\r\n".equals(ending) ? text.replace("\n", "\r\n") : text;
    }

    private static int countOccurrences(String text, String target) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(target, index)) >= 0) {
            count++;
            index += target.length();
        }
        return count;
    }

    /**
     * 保留开头若干行的截断，遇到行数或字节上限即停止，不返回半行。
     */
    private static Truncation truncateHead(String content) {
        String[] lines = splitLines(content);
        if (!content.isEmpty() && lines.length == 0) {
            lines = new String[]{content};
        }
        int total = lines.length;
        int totalBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (total <= MAX_LINES && totalBytes <= MAX_BYTES) {
            return new Truncation(content, false, false, total, total);
        }
        if (lines.length > 0 && lines[0].getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            return new Truncation("", true, true, total, 0);
        }
        List<String> kept = new ArrayList<>();
        int bytes = 0;
        for (int i = 0; i < lines.length && i < MAX_LINES; i++) {
            int lineBytes = lines[i].getBytes(StandardCharsets.UTF_8).length + (i > 0 ? 1 : 0);
            if (bytes + lineBytes > MAX_BYTES) {
                break;
            }
            kept.add(lines[i]);
            bytes += lineBytes;
        }
        return new Truncation(String.join("\n", kept), true, false, total, kept.size());
    }

    /**
     * 保留末尾若干行的截断，遇到行数或字节上限即停止。
     */
    private static Truncation truncateTail(String content) {
        String[] lines = splitLines(content);
        int total = lines.length;
        int totalBytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (total <= MAX_LINES && totalBytes <= MAX_BYTES) {
            return new Truncation(content, false, false, total, total);
        }
        List<String> kept = new ArrayList<>();
        int bytes = 0;
        for (int i = lines.length - 1; i >= 0 && kept.size() < MAX_LINES; i--) {
            int lineBytes = lines[i].getBytes(StandardCharsets.UTF_8).length + (kept.isEmpty() ? 0 : 1);
            if (bytes + lineBytes > MAX_BYTES) {
                break;
            }
            kept.add(0, lines[i]);
            bytes += lineBytes;
        }
        return new Truncation(String.join("\n", kept), true, false, total, kept.size());
    }

    /**
     * @author yyf
     * @description 截断结果：内容、是否截断、首行是否超限、原始行数与输出行数
     */
    private record Truncation(String content, boolean truncated, boolean firstLineExceedsLimit,
                              int totalLines, int outputLines) {
    }
}
