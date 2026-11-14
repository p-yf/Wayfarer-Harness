package com.yf.wayfarer.change;

/**
 * 一次变更提交里的单个文件。
 *
 * @author yyf
 * @description 记录变更类型与增删行数；二进制文件不统计行数，只标记 binary
 */
public record ChangeFile(String path, String changeType, int insertions, int deletions, boolean binary) {
}
