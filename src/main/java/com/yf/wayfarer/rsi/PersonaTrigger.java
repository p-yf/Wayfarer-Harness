package com.yf.wayfarer.rsi;

/**
 * L3 画像五级触发器：五条独立路径按优先级判定画像是否需要重生。
 *
 * <p>画像（persona）更新不是简单阈值，而是五条独立路径按优先级判定：</p>
 * <ol>
 *   <li><b>P1 显式请求</b>：L2 场景萃取输出带 {@code [PERSONA_UPDATE_REQUEST]} 信号，
 *       说明场景内容已明确指向"用户画像需要改"，立即重生画像；</li>
 *   <li><b>P2 冷启动</b>：已有场景库 + 从未生成过画像 + 有已处理记忆——系统刚起步，
 *       素材第一次够用，生成首版画像；</li>
 *   <li><b>P2.5 恢复</b>：画像文件存在但正文为空/丢失（被误删、写坏），重生补救；</li>
 *   <li><b>P3 首场景</b>：场景库中恰好只有 1 个场景且有新记忆——第一个场景刚成形，
 *       是画像的天然起点；</li>
 *   <li><b>P4 阈值</b>：自上次画像以来新增记忆 ≥ personaTriggerEveryN，例行更新。</li>
 * </ol>
 *
 * <p>纯函数：输入状态快照、输出判定结果，不碰文件与游标——状态采集由
 * {@link RsiSedimentPipeline} 完成，便于单测逐级覆盖。</p>
 *
 * @author yyf
 * @description evaluate()：按 P1 &gt; P2 &gt; P2.5 &gt; P3 &gt; P4 优先级逐级判定，
 * 命中即返回（带理由），全不满足返回 NONE
 */
public final class PersonaTrigger {

    private PersonaTrigger() {
    }

    /**
     * 判定输入状态快照（由 pipeline 从文件系统与游标簿记采集）。
     *
     * @author yyf
     * @description 五级触发所需的最小事实集：信号标志、场景库状态、画像文件状态、
     * 记忆计数与阈值
     */
    public record State(boolean personaRequest,
                        boolean hasScenes,
                        boolean personaEverGenerated,
                        boolean personaFileExists,
                        boolean personaBodyBlank,
                        int sceneCount,
                        int totalMemories,
                        int memoriesSincePersona,
                        int thresholdEveryN) {
    }

    /**
     * 判定结果：命中级别 + 人话理由（进日志与 commit message）。
     *
     * @author yyf
     * @description level=NONE 表示本轮不更新画像
     */
    public record Decision(Level level, String reason) {

        public boolean triggered() {
            return level != Level.NONE;
        }
    }

    /**
     * 触发级别（P 系编号即优先级次序，编号越小越优先）。
     *
     * @author yyf
     * @description P1 显式请求 &gt; P2 冷启动 &gt; P2.5 恢复 &gt; P3 首场景 &gt; P4 阈值 &gt; NONE
     */
    public enum Level {
        P1_REQUEST("P1 显式请求：L2 萃取发出 PERSONA_UPDATE_REQUEST 信号"),
        P2_COLD_START("P2 冷启动：已有场景库与记忆但从未生成过画像"),
        P2_5_RECOVER("P2.5 恢复：画像文件存在但正文为空/丢失"),
        P3_FIRST_SCENE("P3 首场景：第一个场景刚成形且有新记忆"),
        P4_THRESHOLD("P4 阈值：自上次画像以来新增记忆达到阈值"),
        NONE("不触发");

        private final String reason;

        Level(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * 按优先级逐级判定。
     */
    public static Decision evaluate(State s) {
        // P1：L2 萃取显式要求更新画像——最高优先级，无条件触发
        if (s.personaRequest()) {
            return new Decision(Level.P1_REQUEST, Level.P1_REQUEST.reason());
        }
        // P2：冷启动——场景库与记忆都已就绪但画像从未生成过
        if (s.hasScenes() && !s.personaEverGenerated() && s.totalMemories() > 0) {
            return new Decision(Level.P2_COLD_START, Level.P2_COLD_START.reason());
        }
        // P2.5：恢复——画像文件在但正文丢了，重生补救
        if (s.personaFileExists() && s.personaBodyBlank()) {
            return new Decision(Level.P2_5_RECOVER, Level.P2_5_RECOVER.reason());
        }
        // P3：首场景——场景库恰好 1 个场景且本轮有新记忆
        if (s.sceneCount() == 1 && s.memoriesSincePersona() > 0) {
            return new Decision(Level.P3_FIRST_SCENE, Level.P3_FIRST_SCENE.reason());
        }
        // P4：例行阈值——累计新增记忆达到 personaTriggerEveryN
        if (s.memoriesSincePersona() > 0 && s.memoriesSincePersona() >= s.thresholdEveryN()) {
            return new Decision(Level.P4_THRESHOLD, Level.P4_THRESHOLD.reason());
        }
        return new Decision(Level.NONE, Level.NONE.reason());
    }
}
