package com.yf.wayfarer.rsi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yf.wayfarer.rsi.model.MemoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆词面检索核心（零 embedding 依赖）。
 *
 * <p>三处共用（规格 8.1 / 8.3）：</p>
 * <ul>
 *   <li>{@code MemoryRecallHandler} 被动召回（run 级一次，top-k + 预算截断）；</li>
 *   <li>{@code memory_search} 工具主动检索（agent 执行中按需深挖）；</li>
 *   <li>L1 提取时的既有记忆摘要（不直接用，但 tags 生成参考同一套分词）。</li>
 * </ul>
 *
 * <p>匹配算法：查询分词（拉丁词 + 中文词组及其二元组）→ 与条目 tags（权重 2）/ 内容
 * （权重 1）求命中 → 命中分 × 条目 score 排序。中文无分词器，二元组（bigram）是
 * 无依赖条件下召回率的务实折衷。</p>
 *
 * <p>L2 场景库：检索走 {@link SceneIndex}（scene_index.json 摘要 + 场景正文），
 * 命中时场景热度 heat +1（被检索命中即升温）；被动召回块（recallBlock）按分层
 * 注入约定只含 L1——L3 全文与 L2 索引由
 * {@link RsiProfileHandler} 会话初始化时装进 system prompt，L2 正文走
 * memory_search 工具按需拉取。</p>
 *
 * @author yyf
 * @description tokenize()：查询分词；search()：按层（l1/l2/l3/all）检索排序；
 * recallBlock()：被动召回组装 {@code <rsi-memory>} 数据块（只含 L1 top-k，预算截断）
 */
@Service
public class MemorySearcher {

    private static final Logger log = LoggerFactory.getLogger(MemorySearcher.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 拉丁词（含下划线/数字）：编程语境的关键词主要来源。 */
    private static final Pattern LATIN = Pattern.compile("[A-Za-z0-9_]{2,}");

    /** 中文词组：连续汉字 2 个以上。 */
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]{2,}");

    /** 拉丁停用词：少量高频虚词，避免全文命中噪音。 */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "have", "has",
            "what", "when", "how", "why", "who", "are", "was", "you", "your", "can", "not");

    private final RsiPaths paths;
    private final RsiProperties properties;
    private final SceneIndex sceneIndex;

    public MemorySearcher(RsiPaths paths, RsiProperties properties, SceneIndex sceneIndex) {
        this.paths = paths;
        this.properties = properties;
        this.sceneIndex = sceneIndex;
    }

    /**
     * 查询分词：拉丁词（去停用词、转小写）+ 中文词组 + 中文二元组，去重保序。
     */
    public List<String> tokenize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Matcher latin = LATIN.matcher(query);
        while (latin.find()) {
            String word = latin.group().toLowerCase(Locale.ROOT);
            if (!STOPWORDS.contains(word)) {
                tokens.add(word);
            }
        }
        Matcher cjk = CJK.matcher(query);
        while (cjk.find()) {
            String run = cjk.group();
            tokens.add(run);
            // 二元组：中文短词（如"游标"）在长词组（"游标簿记"）中也能命中
            for (int i = 0; i + 2 <= run.length(); i++) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        return List.copyOf(tokens);
    }

    /**
     * 检索：按层过滤、词面打分排序取前 limit 条。
     *
     * @param agent agent 名称（只检索该 agent 自己的 store）
     * @param layer l1 / l2 / l3 / all
     */
    public List<Hit> search(String agent, String query, String layer, int limit) {
        List<String> tokens = tokenize(query);
        List<Hit> hits = new ArrayList<>();
        if (!tokens.isEmpty()) {
            String scope = layer == null || layer.isBlank() ? "all" : layer.strip().toLowerCase(Locale.ROOT);
            if (scope.equals("all") || scope.equals("l1")) {
                hits.addAll(searchL1(agent, tokens));
            }
            if (scope.equals("all") || scope.equals("l2")) {
                hits.addAll(searchL2(agent, tokens));
            }
            if (scope.equals("all") || scope.equals("l3")) {
                hits.addAll(searchL3(agent, tokens));
            }
            hits.sort(Comparator.comparingInt(Hit::score).reversed());
        }
        return hits.size() <= limit ? hits : hits.subList(0, limit);
    }

    /**
     * 被动召回块（MemoryRecallHandler 用）：按分层注入约定，
     * <b>只含 L1 top-k</b>（逐轮检索注入）——L3 全文与 L2 索引由 RsiProfileHandler 会话
     * 初始化时装进 system prompt（护 KV-cache），L2 正文走 memory_search 按需拉取。
     * 总预算（字符近似 token）内截断；无命中返回空串（不注入）。
     */
    public String recallBlock(String agent, String query, int budget) {
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        int used = 0;
        // L1：命中分排序取 top-k
        for (Hit hit : searchL1(agent, tokens)) {
            if (hit.score() <= 0) {
                continue;
            }
            String line = "[L1][" + hit.tagsText() + "] " + hit.content() + "\n";
            if (used + line.length() > budget) {
                break;
            }
            block.append(line);
            used += line.length();
        }
        return block.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 分层检索                                                              */
    /* ------------------------------------------------------------------ */

    private List<Hit> searchL1(String agent, List<String> tokens) {
        Path file = paths.memoryDir(agent).resolve("l1.jsonl");
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                MemoryItem item;
                try {
                    item = MAPPER.readValue(line, MemoryItem.class);
                } catch (IOException e) {
                    continue;                                   // 单行损坏跳过，不拖垮整个检索
                }
                int tagHits = countHits(tokens, Set.copyOf(item.tags()));
                int contentHits = countHits(tokens, null, item.content());
                int score = (tagHits * 2 + contentHits) * Math.max(1, item.score());
                if (score > 0) {
                    hits.add(new Hit("l1", item.id(), item.content(),
                            item.tags(), item.updatedAt(), null, score, null));
                }
            }
        } catch (IOException e) {
            // 读失败按无记忆处理，检索是纯增益能力不能反过来影响主流程
            log.warn("RSI L1 检索读取失败 agent={}", agent, e);
        }
        return hits;
    }

    /**
     * L2 场景库检索：走 SceneIndex 的摘要索引做词面打分（场景名 + summary），
     * 命中后读场景正文返回（正文片段，供 memory_search 按需拉取），
     * 并把该场景热度 heat +1（被检索命中即升温，热度供场景整理与索引排序参考）。
     */
    private List<Hit> searchL2(String agent, List<String> tokens) {
        Map<String, SceneIndex.Entry> index = sceneIndex.loadOrRebuild(agent);
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, SceneIndex.Entry> entry : index.entrySet()) {
            String name = entry.getKey();
            SceneIndex.Entry meta = entry.getValue();
            int nameHits = countHits(tokens, Set.of(name.toLowerCase(Locale.ROOT)));
            int summaryHits = countHits(tokens, null, meta.summary());
            int score = nameHits * 2 + summaryHits;
            if (score <= 0) {
                continue;
            }
            String body = readSceneBody(agent, meta.file());
            if (body == null || body.isBlank()) {
                continue;
            }
            hits.add(new Hit("l2", null, body.strip(), List.of(name),
                    meta.updated(), null, score, meta.file()));
            // 检索命中即升温（文件 META 与索引同步 +1）
            sceneIndex.incrementHeat(agent, name);
        }
        return hits;
    }

    /**
     * 读取场景文件正文（SceneFormat 解析，剥掉 META 与一级标题）。
     */
    private String readSceneBody(String agent, String fileName) {
        Path file = sceneIndex.scenesDir(agent).resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return SceneFormat.parse(Files.readString(file, StandardCharsets.UTF_8),
                    fileName).body();
        } catch (IOException | RuntimeException e) {
            log.warn("RSI 场景正文读取失败 file={}", file, e);
            return null;
        }
    }

    private List<Hit> searchL3(String agent, List<String> tokens) {
        String profile = readProfile(agent);
        if (profile == null || profile.isBlank()) {
            return List.of();
        }
        int contentHits = countHits(tokens, null, profile);
        if (contentHits <= 0) {
            return List.of();
        }
        return List.of(new Hit("l3", null, profile.strip(), List.of(), "", null,
                contentHits, "persona.md"));
    }

    /**
     * 读取 L3 画像全文（persona.md；不存在或读失败返回 null）。
     *
     * <p>public：RsiProfileHandler 会话初始化装配 system prompt 时复用同一读取口径。</p>
     */
    public String readProfile(String agent) {
        Path file = paths.memoryDir(agent).resolve("persona.md");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 工具方法                                                              */
    /* ------------------------------------------------------------------ */

    /**
     * 计数命中：targets 非空时按集合精确匹配，否则在 content 里做包含匹配。
     */
    private static int countHits(List<String> tokens, Set<String> targets, String... contents) {
        int hits = 0;
        for (String token : tokens) {
            if (targets != null) {
                if (containsToken(targets, token)) {
                    hits++;
                }
            } else {
                for (String content : contents) {
                    if (content != null && content.contains(token)) {
                        hits++;
                        break;
                    }
                }
            }
        }
        return hits;
    }

    /**
     * tags 集合匹配：tag 精确等于 token，或长 tag 包含 token（中文二元组场景）。
     */
    private static boolean containsToken(Set<String> tags, String token) {
        for (String tag : tags) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (lower.equals(token) || (token.length() >= 2 && lower.contains(token))) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> sortedByMtime(List<Path> files) {
        List<Path> copy = new ArrayList<>(files);
        copy.sort(Comparator.comparingLong(MemorySearcher::mtimeOf));
        return copy;
    }

    private static long mtimeOf(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * @author yyf
     * @description 检索命中：layer（l1/l2/l3）、条目 id（L1）、内容、tags、更新时间、
     * 来源会话（L2）、相关度得分与来源文件（L2/L3）
     */
    public record Hit(String layer, String id, String content, List<String> tags,
                      String updatedAt, String sessionId, int score, String file) {

        /**
         * tags 的展示形态：#tag1 #tag2。
         */
        public String tagsText() {
            if (tags == null || tags.isEmpty()) {
                return "";
            }
            return String.join(" ", tags.stream().map(tag -> "#" + tag).toList());
        }
    }
}
