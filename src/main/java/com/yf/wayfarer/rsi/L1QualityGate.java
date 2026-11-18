package com.yf.wayfarer.rsi;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * L1 消息级质量门：逐条判定消息文本是否值得进入 L1 提取窗口。
 *
 * <p>L0 → L1 之间先做一层消息洗选：不值得提取的消息根本不进提取窗口，
 * 既省掉无效 token 也避免噪音记忆污染原子记忆库。规则共五条：</p>
 * <ol>
 *   <li>空白消息不提取；</li>
 *   <li>斜杠命令（{@code /xxx}）是控制信号不是对话内容，不提取；</li>
 *   <li>框架噪音（NO_REPLY / [DONE] / bootstrap 等协议占位文本），不提取；</li>
 *   <li>纯符号串（1~5 个字符、不含任何字母数字/CJK），如 "!!!"、"。。。"，不提取；</li>
 *   <li>纯问号串（任意长度的 ？/? 组合），不提取。</li>
 * </ol>
 *
 * <p>纯函数、零依赖，方便单测。L0 层的 shouldCaptureL0（消息是否入会话存储）由
 * wayfarer 会话机制天然满足——进入沉淀管线的都是真实用户/助手消息，只需 L1 层门。</p>
 *
 * @author yyf
 * @description shouldExtractL1()：单条消息文本是否值得进入 L1 提取窗口；
 * 全部规则为保守过滤（宁可放过不可错杀），命中任一规则即排除
 */
public final class L1QualityGate {

    /** 框架噪音标记（小写比较）：协议占位文本，出现即排除。 */
    private static final Set<String> NOISE_TOKENS = Set.of(
            "no_reply", "noreply", "[done]", "[no reply]", "bootstrap",
            "system_boot", "init_ok", "<<human_conversation>>");

    /** 斜杠命令：以 / 开头且首段无空白（如 /help、/compact）。 */
    private static final Pattern SLASH_COMMAND = Pattern.compile("^/\\S+\\s*$");

    /** 词字符：字母、数字、下划线或 CJK——纯符号判定时把这些剥掉再看剩余。 */
    private static final Pattern WORD_OR_CJK = Pattern.compile("[a-zA-Z0-9_\\u4e00-\\u9fff]");

    /** 纯问号串：整条只由中英文问号（可夹空白）组成。 */
    private static final Pattern ONLY_QUESTIONS = Pattern.compile("^[?？\\s]+$");

    private L1QualityGate() {
    }

    /**
     * 判断单条消息文本是否值得进入 L1 提取窗口。
     *
     * @param text 消息文本（原始内容，不做预处理）
     * @return true = 进入提取窗口；false = 质量门过滤
     */
    public static boolean shouldExtractL1(String text) {
        if (text == null) {
            return false;
        }
        String value = text.strip();
        // 规则 1：空白消息
        if (value.isEmpty()) {
            return false;
        }
        // 规则 2：斜杠命令（控制信号，不是对话内容）
        if (SLASH_COMMAND.matcher(value).matches()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        // 规则 3：框架噪音占位文本
        for (String noise : NOISE_TOKENS) {
            if (lower.contains(noise)) {
                return false;
            }
        }
        // 规则 4：纯符号串（1~5 字符且不含词字符/CJK，如 "!!!"、"。。。~"）
        if (value.length() <= 5 && !WORD_OR_CJK.matcher(value).find()) {
            return false;
        }
        // 规则 5：纯问号串（任意长度）
        return !ONLY_QUESTIONS.matcher(value).matches();
    }
}
