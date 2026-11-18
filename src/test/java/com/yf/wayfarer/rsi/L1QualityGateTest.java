package com.yf.wayfarer.rsi;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1QualityGate 单测（五条洗选规则）。
 *
 * @author yyf
 * @description 覆盖空白/斜杠命令/框架噪音/纯符号/纯问号五类排除与正常文本放行
 */
class L1QualityGateTest {

    @Test
    void 空白消息不提取() {
        assertFalse(L1QualityGate.shouldExtractL1(null));
        assertFalse(L1QualityGate.shouldExtractL1(""));
        assertFalse(L1QualityGate.shouldExtractL1("   \n  "));
    }

    @Test
    void 斜杠命令不提取() {
        assertFalse(L1QualityGate.shouldExtractL1("/help"));
        assertFalse(L1QualityGate.shouldExtractL1("/compact  "));
        // 带参数的句子是自然语言，放行
        assertTrue(L1QualityGate.shouldExtractL1("/path 目录下有什么"));
    }

    @Test
    void 框架噪音不提取() {
        assertFalse(L1QualityGate.shouldExtractL1("NO_REPLY"));
        assertFalse(L1QualityGate.shouldExtractL1("[DONE]"));
        assertFalse(L1QualityGate.shouldExtractL1("bootstrap ok"));
        assertFalse(L1QualityGate.shouldExtractL1("<<human_conversation>>"));
    }

    @Test
    void 纯符号串不提取() {
        assertFalse(L1QualityGate.shouldExtractL1("!!!"));
        assertFalse(L1QualityGate.shouldExtractL1("。。。~"));
        // 超过 5 个字符的符号串保守放行（宁放过不错杀）
        assertTrue(L1QualityGate.shouldExtractL1("!!!!!!!"));
    }

    @Test
    void 纯问号串不提取() {
        assertFalse(L1QualityGate.shouldExtractL1("???"));
        assertFalse(L1QualityGate.shouldExtractL1("？ ？？ ??"));
        // 有实际内容的问句放行
        assertTrue(L1QualityGate.shouldExtractL1("这个报错怎么修？"));
    }

    @Test
    void 正常对话放行() {
        assertTrue(L1QualityGate.shouldExtractL1("项目用 JDK 21，构建工具是 Gradle"));
        assertTrue(L1QualityGate.shouldExtractL1("好的，我记住了"));
    }
}
