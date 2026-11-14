package com.yf.wayfarer.change;

import java.util.List;
import java.util.Map;

/**
 * 一次回退的结果。
 *
 * @author yyf
 * @description 记录回退提交 id、实际回退的路径，以及因「之后被别人改过」而跳过的冲突路径
 */
public record RevertOutcome(String commitId, List<String> files, List<Map<String, Object>> conflicts) {
}
