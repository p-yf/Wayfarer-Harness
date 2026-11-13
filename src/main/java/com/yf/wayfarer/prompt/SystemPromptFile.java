package com.yf.wayfarer.prompt;

/**
 * 一个系统提示词文件。
 *
 * @author yyf
 * @description 由 agent 名称、提示词名称与顺序共同确定唯一文件
 */
public record SystemPromptFile(String agent, String name, int order, String content) {
}
