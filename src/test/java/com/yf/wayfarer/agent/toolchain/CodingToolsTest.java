package com.yf.wayfarer.agent.toolchain;

import com.yf.wayfarer.loop.AgentContext;
import com.yf.wayfarer.toolcall.ToolCall;
import com.yf.wayfarer.toolcall.ToolCallInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编码工具测试，全部基于真实文件系统与真实进程。
 *
 * @author yyf
 * @description 覆盖 read 分段读取、bash 命令执行、edit 唯一替换、write 自动建目录
 */
class CodingToolsTest {

    @TempDir
    Path workspace;

    private ToolCall toolCall;
    private AgentContext context;

    @BeforeEach
    void setUp() {
        toolCall = new ToolCall();
        toolCall.parse(new CodingTools());
        context = new AgentContext().put(AgentContext.WORKSPACE, workspace.toString());
    }

    @Test
    void 写入后读取完整内容() throws IOException {
        Object written = call("write", "{\"path\":\"a.txt\",\"content\":\"hello\\nworld\"}");
        assertTrue(String.valueOf(written).contains("a.txt"), String.valueOf(written));
        assertEquals("hello\nworld", Files.readString(workspace.resolve("a.txt"), StandardCharsets.UTF_8));

        Object read = call("read", "{\"path\":\"a.txt\"}");
        assertEquals("hello\nworld", read);
    }

    @Test
    void 按起始行与行数分段读取() {
        String content = "line1\nline2\nline3\nline4";
        call("write", "{\"path\":\"b.txt\",\"content\":\"" + content.replace("\n", "\\n") + "\"}");

        Object read = call("read", "{\"path\":\"b.txt\",\"offset\":2,\"limit\":2}");

        assertTrue(String.valueOf(read).startsWith("line2\nline3"), String.valueOf(read));
        assertTrue(String.valueOf(read).contains("offset=4"), String.valueOf(read));
    }

    @Test
    void 编辑要求唯一匹配并保留原换行风格() throws IOException {
        Path file = workspace.resolve("c.txt");
        Files.writeString(file, "alpha\r\nbeta\r\ngamma", StandardCharsets.UTF_8);

        Object result = call("edit", "{\"path\":\"c.txt\",\"oldText\":\"beta\",\"newText\":\"BETA\"}");

        assertTrue(String.valueOf(result).contains("已替换"), String.valueOf(result));
        assertEquals("alpha\r\nBETA\r\ngamma", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void 编辑多处匹配时报错且不修改文件() throws IOException {
        Path file = workspace.resolve("d.txt");
        Files.writeString(file, "same\nsame", StandardCharsets.UTF_8);

        Object result = call("edit", "{\"path\":\"d.txt\",\"oldText\":\"same\",\"newText\":\"other\"}");

        assertTrue(String.valueOf(result).contains("出现 2 次"), String.valueOf(result));
        assertEquals("same\nsame", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void 编辑未匹配时报错() throws IOException {
        Path file = workspace.resolve("e.txt");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);

        Object result = call("edit", "{\"path\":\"e.txt\",\"oldText\":\"not-exist\",\"newText\":\"x\"}");

        assertTrue(String.valueOf(result).contains("未在"), String.valueOf(result));
        assertEquals("abc", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void 写入时自动创建父目录() throws IOException {
        call("write", "{\"path\":\"nested/dir/f.txt\",\"content\":\"ok\"}");

        assertEquals("ok", Files.readString(workspace.resolve("nested/dir/f.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void 执行shell命令并返回输出() {
        Object result = call("bash", "{\"command\":\"echo wayfarer\"}");

        assertTrue(String.valueOf(result).contains("wayfarer"), String.valueOf(result));
    }

    private Object call(String name, String arguments) {
        return toolCall.call(new ToolCallInfo("call_" + name, name, arguments), context);
    }
}
