package com.yf.wayfarer.change;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 变更记录配置。
 *
 * @author yyf
 * @description 对应 application.yml 里的 wayfarer.changes 节点：是否开启、影子仓库根目录、扫描排除项
 */
@ConfigurationProperties(prefix = "wayfarer.changes")
public class ChangeProperties {

    /**
     * 是否记录变更，关闭后不再对工作区做运行前后的脏文件比对。
     */
    private boolean enabled = true;

    /**
     * 影子仓库根目录，默认放在项目下的 .wayfarer/shadow，需加入 .gitignore。
     */
    private String root = "./.wayfarer/shadow";

    /**
     * 扫描排除项，写进影子仓库的 info/exclude，不会修改工作区自己的 .gitignore。
     */
    private List<String> exclude = new ArrayList<>(List.of(
            ".git", ".wayfarer", "sessions", "systemprompt", "target", "node_modules"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public List<String> getExclude() {
        return exclude;
    }

    public void setExclude(List<String> exclude) {
        this.exclude = exclude;
    }
}
