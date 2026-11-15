package com.yf.wayfarer.change;

import java.util.List;

/**
 * 一次回退的结果。
 *
 * @author yyf
 * @description 记录回退提交 id 与实际回退的路径；回退按「覆盖优先」执行，工作区里被用户或其它会话后改的内容也会被覆盖，不作冲突跳过
 */
public record RevertOutcome(String commitId, List<String> files) {
}
