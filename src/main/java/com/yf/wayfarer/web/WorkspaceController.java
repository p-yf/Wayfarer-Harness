package com.yf.wayfarer.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 工作区目录浏览接口。
 *
 * @author yyf
 * @description 列出服务器上的子目录，供页面内的工作区选择器逐级浏览；
 * 不依赖服务进程的图形环境，任何运行方式下都能用
 */
@RestController
@RequestMapping("/api/workspace")
public class WorkspaceController {

    @GetMapping("/list")
    public Mono<Map<String, Object>> list(@RequestParam(name = "path", required = false) String path) {
        return Mono.fromCallable(() -> listing(path))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("path", path == null ? "" : path);
                    result.put("error", error.getMessage() == null ? "读取目录失败" : error.getMessage());
                    return Mono.just(result);
                });
    }

    /**
     * 返回目录自身路径、上一级、子目录绝对路径列表；到根目录时附带盘符列表。
     * 未指定路径时回到「此电脑」，只列出各盘符。
     */
    private static Map<String, Object> listing(String path) throws IOException {
        if (path == null || path.isBlank()) {
            Map<String, Object> computer = new LinkedHashMap<>();
            computer.put("path", "");
            computer.put("parent", null);
            computer.put("directories", List.of());
            computer.put("roots", roots());
            return computer;
        }

        Path dir = Path.of(path).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("目录不存在：" + dir);
        }

        List<String> directories;
        try (Stream<Path> children = Files.list(dir)) {
            directories = children
                    .filter(Files::isDirectory)
                    .map(child -> child.toAbsolutePath().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", dir.toString());
        Path parent = dir.getParent();
        result.put("parent", parent == null ? null : parent.toString());
        result.put("directories", directories);
        if (parent == null) {
            result.put("roots", roots());
        }
        return result;
    }

    private static List<String> roots() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("/");
        }
        File[] roots = File.listRoots();
        return roots == null ? List.of() : Arrays.stream(roots).map(File::getAbsolutePath).toList();
    }
}
