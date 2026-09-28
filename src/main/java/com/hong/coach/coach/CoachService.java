package com.hong.coach.coach;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * AI 教练：把引擎算出来的东西翻译成教练口吻的人话。
 * <p>
 * 两条链路：
 * <ul>
 *   <li>{@link #explainWeakMove(TeachContext)} 用户走了明显亏棋时主动点评；</li>
 *   <li>{@link #answer(String, TeachContext)} 用户主动提问时结合盘面与引擎分析作答。</li>
 * </ul>
 * 大模型不可用、超时或返回异常时，一律降级为本地按引擎数据拼出来的提示，
 * 保证对局流程永远不会因为大模型而中断。
 */
@Slf4j
@Service
public class CoachService {

    /** 去掉模型偶尔带出来的英文坐标，例如「马二进三（b0c2）」 */
    private static final Pattern UCI_IN_BRACKETS = Pattern.compile("[（(]\\s*[a-iA-I]\\d[a-iA-I]\\d\\s*[)）]");
    private static final Pattern MARKDOWN_NOISE = Pattern.compile("(\\*\\*|^#{1,6}\\s*|`+)", Pattern.MULTILINE);

    private static final String ROLE_BLOCK = """
            【你是谁】
            你是「棋叔」，一位陪学员下中国象棋的老教练，就坐在学员对面。

            【必须隐藏的事】
            你背后有一个强力象棋引擎在替你算棋。绝对不要提到「引擎」「AI」「程序」「FEN」「深度」「主变」这些词，
            也不要写出 b0c2 这类英文字母坐标。把你算出来的结论，直接当成你自己的判断说出来。

            【讲话风格】
            - 中文口语，像真人教练在旁边说话，可以直接称呼「你」。
            - 先给结论，再给理由；一次只讲 1~2 个要点，不要面面俱到。
            - 直接输出正文，不要标题、不要列表符号、不要加粗，不要用引号把整段括起来。
            """;

    /** 记谱说明：红黑双方纵线编号不同，模型很容易搞混，必须显式说明 */
    private static final String NOTATION_HINT =
            "（记谱说明：红方纵线用「一~九」，黑方纵线用「1~9」。着法记录里已经标明每一步是哪一方走的，"
                    + "不要张冠李戴。谈棋时也请用这种记谱，不要去推算屏幕上的行列坐标。）";

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final CoachProperties props;

    private final ExecutorService llmPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "coach-llm");
        t.setDaemon(true);
        return t;
    });

    public CoachService(ObjectProvider<ChatModel> chatModelProvider, CoachProperties props) {
        this.chatModelProvider = chatModelProvider;
        this.props = props;
    }

    public boolean isAvailable() {
        return props.isEnabled() && chatModelProvider.getIfAvailable() != null;
    }

    // ==================== 对外能力 ====================

    /**
     * 主动讲解：用户走了一步亏棋
     */
    public String explainWeakMove(TeachContext ctx) {
        if (!isAvailable()) {
            return localComment(ctx);
        }
        return ask(buildExplainPrompt(ctx), () -> localComment(ctx));
    }

    /**
     * 用户主动提问
     */
    public String answer(String question, TeachContext ctx) {
        if (!isAvailable()) {
            return localAnswer(ctx);
        }
        return ask(buildQuestionPrompt(question, ctx), () -> localAnswer(ctx));
    }

    /**
     * 开局致辞，让页面一打开就有教练在场的感觉
     */
    public String greeting(TeachContext ctx) {
        String fallbackText = "我是棋叔。你执" + ctx.playerSideText() + "，走出好棋我夸，走出亏棋我提醒，随时可以问我。";
        if (!isAvailable()) {
            return fallbackText;
        }
        StringBuilder sb = new StringBuilder(ROLE_BLOCK);
        sb.append("\n【这盘棋的情况】\n");
        sb.append("学员执").append(ctx.playerSideText()).append("，对局刚刚开始。\n");
        if (ctx.getBoardDiagram() != null && !ctx.getBoardDiagram().isBlank()) {
            sb.append("棋盘（上方是黑方，下方是红方）：\n").append(ctx.getBoardDiagram()).append('\n');
        }
        sb.append("\n【要你做的事】\n");
        sb.append("用一两句话跟学员打个招呼，说明你会在他走亏棋时提醒、也可以随时提问，控制在 60 字以内。\n");
        sb.append("不要自称 AI，不要写列表。");
        return ask(sb.toString(), () -> fallbackText);
    }

    // ==================== 提示词构造 ====================

    private String buildExplainPrompt(TeachContext ctx) {
        StringBuilder sb = new StringBuilder(ROLE_BLOCK);
        sb.append("\n【这盘棋的情况】\n");
        // 红黑两边都可能由学员自己动手走，所以这里点明「刚走的这一手是哪一方的」，
        // 而不是笼统说「学员执红」——否则模型会把黑方的着法按红方视角点评。
        sb.append("刚才这一手是").append(ctx.playerSideText()).append("走的，现在是")
                .append(ctx.getPhase()).append("阶段。\n");
        if (!ctx.getMoveList().isEmpty()) {
            sb.append("双方着法依次是：").append(String.join("、", tail(ctx.getMoveList(), 24))).append("。\n");
            sb.append(NOTATION_HINT).append('\n');
        }
        if (ctx.getBoardDiagram() != null && !ctx.getBoardDiagram().isBlank()) {
            sb.append("棋盘（上方是黑方，下方是红方）：\n").append(ctx.getBoardDiagram()).append('\n');
        }

        sb.append("\n【刚才这一步】\n");
        sb.append("学员走的是：").append(ctx.getPlayedMove()).append("。\n");
        sb.append("走之前：").append(ctx.evalText(ctx.getEvalBefore())).append("。\n");
        sb.append("走之后：").append(ctx.evalText(ctx.getEvalAfter())).append("。\n");
        sb.append("这步棋让学员白白损失了大约 ")
                .append(String.format("%.2f", ctx.getEvalLoss() / 100.0))
                .append(" 个兵的价值，属于「").append(ctx.getQuality()).append("」。\n");
        if (ctx.getBetterMove() != null) {
            sb.append("你自己算下来，走 ").append(ctx.getBetterMove()).append(" 要好得多");
            if (!ctx.getPvChinese().isEmpty()) {
                sb.append("，之后大致会走成：").append(String.join("、", tail(ctx.getPvChinese(), 8)));
            }
            sb.append("。\n");
        }
        if (ctx.isPlayerInCheck()) {
            sb.append("另外注意，学员的老将正处于被将军状态。\n");
        }

        sb.append("\n【要你做的事】\n");
        sb.append("点评刚才这步「").append(ctx.getPlayedMove()).append("」，控制在 130 字以内，说清三件事：\n");
        sb.append("第一，这步棋的问题出在哪，尽量点明棋盘上具体是哪一侧、哪一路的棋子；\n");
        sb.append("第二，它为什么会让你吃亏；\n");
        if (ctx.getBetterMove() != null) {
            sb.append("第三，换成 ").append(ctx.getBetterMove()).append(" 好在什么地方。\n");
        }
        return sb.toString();
    }

    private String buildQuestionPrompt(String question, TeachContext ctx) {
        StringBuilder sb = new StringBuilder(ROLE_BLOCK);
        sb.append("\n【这盘棋的情况】\n");
        sb.append("学员执").append(ctx.playerSideText()).append("，现在是").append(ctx.getPhase()).append("阶段");

        if (ctx.isGameOver()) {
            sb.append("，对局已经结束：").append(ctx.getResultText() == null ? "已分胜负" : ctx.getResultText()).append("。\n");
        } else if (ctx.getTurn() == ctx.getPlayerSide()) {
            sb.append("，现在轮到学员走棋。\n");
        } else {
            sb.append("，现在轮到对手走棋。\n");
        }

        if (!ctx.getMoveList().isEmpty()) {
            sb.append("双方着法依次是：").append(String.join("、", tail(ctx.getMoveList(), 24))).append("。\n");
            sb.append(NOTATION_HINT).append('\n');
        }
        if (ctx.getBoardDiagram() != null && !ctx.getBoardDiagram().isBlank()) {
            sb.append("棋盘（上方是黑方，下方是红方）：\n").append(ctx.getBoardDiagram()).append('\n');
        }
        sb.append("当前的形势：").append(ctx.evalText(ctx.getCurrentEvalCp())).append("。\n");
        if (ctx.getSuggestedMove() != null) {
            // 轮到谁走决定这句话的主语：轮到学员是「你最好走」，轮到对手则是「对手最好走」
            sb.append(ctx.getTurn() == ctx.getPlayerSide()
                    ? "你自己算下来，学员现在最好走 "
                    : "你自己算下来，现在轮到对手走，它最好的一步是 ");
            sb.append(ctx.getSuggestedMove());
            if (!ctx.getSuggestedPvChinese().isEmpty()) {
                sb.append("，之后大致是：").append(String.join("、", tail(ctx.getSuggestedPvChinese(), 8)));
            }
            sb.append("。\n");
        }
        if (ctx.isPlayerInCheck()) {
            sb.append("学员的老将正被将军，必须先解将。\n");
        }

        sb.append("\n【学员的提问】\n").append(question == null ? "这盘棋我该怎么下？" : question.trim()).append('\n');
        sb.append("""

                【要你做的事】
                直接回答学员的问题，200 字以内：
                - 不要重复他的问题，不要客套，不要写列表。
                - 尽量用棋盘上的具体位置说话，比如「三路」「你的左翼」这种说法。
                - 如果他问的是「我该怎么走」，就把你算出来的那一步告诉他，并说清思路。
                - 如果他问的和象棋无关，简单回应一句，再把话题带回这盘棋。
                """);
        return sb.toString();
    }

    // ==================== 大模型调用 ====================

    private String ask(String prompt, Supplier<String> fallback) {
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model == null) return fallback.get();
        Future<String> future = llmPool.submit(() -> model.call(prompt));
        try {
            String raw = future.get(props.getTimeoutMs(), TimeUnit.MILLISECONDS);
            String cleaned = clean(raw);
            if (cleaned.isEmpty()) {
                log.warn("大模型返回空内容，降级为本地提示");
                return fallback.get();
            }
            return cleaned;
        } catch (Exception e) {
            future.cancel(true);
            log.warn("大模型调用失败，降级为本地提示：{}", e.getMessage());
            return fallback.get();
        }
    }

    /**
     * 清理模型输出：去掉 Markdown 噪音与漏出来的英文坐标
     */
    private String clean(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = MARKDOWN_NOISE.matcher(s).replaceAll("");
        s = UCI_IN_BRACKETS.matcher(s).replaceAll("");
        return s.trim();
    }

    // ==================== 本地降级文案 ====================

    private String localComment(TeachContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (ctx.getPlayedMove() != null) {
            sb.append("这步「").append(ctx.getPlayedMove()).append("」");
        } else {
            sb.append("刚才这一步");
        }
        sb.append("有点亏");
        if (ctx.getEvalLoss() > 0) {
            sb.append("，大约丢掉了 ").append(String.format("%.2f", ctx.getEvalLoss() / 100.0)).append(" 个兵的先手");
        }
        sb.append("。");
        if (ctx.getBetterMove() != null) {
            sb.append("更值得考虑的是「").append(ctx.getBetterMove()).append("」");
            if (!ctx.getPvChinese().isEmpty()) {
                sb.append("，接下来大致是 ").append(String.join("、", tail(ctx.getPvChinese(), 5)));
            }
            sb.append("。");
        }
        sb.append("（大模型讲解暂时不可用，先按引擎数据给你提示。）");
        return sb.toString();
    }

    private String localAnswer(TeachContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("现在是").append(ctx.evalText(ctx.getCurrentEvalCp())).append("。");
        if (ctx.getSuggestedMove() != null) {
            sb.append("我算下来，你最好走「").append(ctx.getSuggestedMove()).append("」");
            if (!ctx.getSuggestedPvChinese().isEmpty()) {
                sb.append("，之后大致是 ").append(String.join("、", tail(ctx.getSuggestedPvChinese(), 5)));
            }
            sb.append("。");
        } else {
            sb.append("棋盘上还有得下，稳住阵脚别急着兑子。");
        }
        sb.append("（大模型讲解暂时不可用，先按引擎数据给你提示。）");
        return sb.toString();
    }

    private static List<String> tail(List<String> list, int n) {
        if (list == null || list.isEmpty()) return List.of();
        return list.size() <= n ? list : list.subList(list.size() - n, list.size());
    }
}
