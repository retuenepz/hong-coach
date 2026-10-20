package com.hong.coach.coach;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * AI 教练：把引擎算出来的结论翻译成教练口吻的人话。
 * <p>
 * <b>定位是「翻译」，不是「军师」。</b>棋力全部来自引擎，模型既不负责算棋，
 * 也不需要重新推演——它只把已经确定的结论讲清楚。这一点在提示词里是硬约束
 * （见 {@link #ROLE_BLOCK}），在参数上则是把 DeepSeek 的思考关掉
 * （见 {@link DeepSeekLlmClient}）：既没有推演的必要，也就没有等它推演的道理。
 * <p>
 * 每条链路都有两个版本：
 * <ul>
 *   <li><b>流式</b>（{@link #streamExplain} / {@link #streamAnswer} / {@link #streamGreeting}）——
 *       页面实际用这条。大模型是边生成边吐字的，一次全量等待十几秒会让人以为卡死，
 *       所以内容分段推出，前端逐字显示；</li>
 *   <li><b>非流式</b>（{@link #explainWeakMove} / {@link #answer}）—— 保留一份同步版本，
 *       给不支持流式的调用方（curl 调试、旧接口）用。</li>
 * </ul>
 * 大模型不可用、超时或返回异常时，一律降级为本地按引擎数据拼出来的提示，
 * 保证对局流程永远不会因为大模型而中断。
 */
@Slf4j
@Service
public class CoachService {

    private static final String ROLE_BLOCK = """
            【你是谁】
            你是「棋叔」，一位陪学员下中国象棋的老教练，就坐在学员对面。

            【你的棋力从哪来：只翻译，不推演】
            下面给你的每一条棋局结论都已经验证过，就是标准答案。
            - 不要自己重新推演这盘棋，不要另找别的走法，不要质疑或反驳下面的结论。
            - 你的任务只有一件：把结论翻译成学员听得懂的、口语化的话。
            - 结论和你的直觉不一致时，一律以结论为准。
            - 说结论要肯定，不要用「我觉得」「可能」「也许」这类含糊的说法。

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

    private final LlmClient llm;
    private final CoachProperties props;

    public CoachService(LlmClient llm, CoachProperties props) {
        this.llm = llm;
        this.props = props;
    }

    public boolean isAvailable() {
        return props.isEnabled() && llm.available();
    }

    /** 当前生效的大模型厂商，给体检接口展示用 */
    public String providerName() {
        return llm.name();
    }

    /**
     * 给体检接口看的可读描述，例如「deepseek（已就绪）」或「deepseek（未配 key，走本地提示）」
     */
    public String statusText() {
        if (!props.isEnabled()) {
            return llm.name() + "（已在配置里关闭）";
        }
        return llm.available()
                ? llm.name() + "（已就绪）"
                : llm.name() + "（不可用，教练走本地提示）";
    }

    // ==================== 对外能力 ====================

    /**
     * 主动讲解：用户走了一步亏棋
     */
    public String explainWeakMove(TeachContext ctx) {
        return ask(ROLE_BLOCK, buildExplainPrompt(ctx), () -> localComment(ctx));
    }

    /**
     * 用户主动提问
     */
    public String answer(String question, TeachContext ctx) {
        return ask(ROLE_BLOCK, buildQuestionPrompt(question, ctx), () -> localAnswer(ctx));
    }

    // ==================== 流式链路（页面走这条） ====================

    /**
     * 开局致辞，边生成边吐字。
     */
    public CoachStream streamGreeting(TeachContext ctx) {
        String fallbackText = localGreeting(ctx);
        if (!isAvailable()) {
            return CoachStream.local(fallbackText);
        }
        return stream(ROLE_BLOCK, buildGreetingPrompt(ctx), () -> fallbackText);
    }

    /**
     * 走亏棋时的主动点评，边生成边吐字。
     */
    public CoachStream streamExplain(TeachContext ctx) {
        String fallbackText = localComment(ctx);
        if (!isAvailable()) {
            return CoachStream.local(fallbackText);
        }
        return stream(ROLE_BLOCK, buildExplainPrompt(ctx), () -> fallbackText);
    }

    /**
     * 回答用户提问，边生成边吐字。
     */
    public CoachStream streamAnswer(String question, TeachContext ctx) {
        String fallbackText = localAnswer(ctx);
        if (!isAvailable()) {
            return CoachStream.local(fallbackText);
        }
        return stream(ROLE_BLOCK, buildQuestionPrompt(question, ctx), () -> fallbackText);
    }

    // ==================== 提示词构造 ====================

    private String buildGreetingPrompt(TeachContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("【这盘棋的情况】\n");
        sb.append("学员执").append(ctx.playerSideText()).append("，对局刚刚开始。\n");
        if (ctx.getBoardDiagram() != null && !ctx.getBoardDiagram().isBlank()) {
            sb.append("棋盘（上方是黑方，下方是红方）：\n").append(ctx.getBoardDiagram()).append('\n');
        }
        sb.append("\n【要你做的事】\n");
        sb.append("用一两句话跟学员打个招呼，说明你会在他走亏棋时提醒、也可以随时提问，控制在 60 字以内。\n");
        sb.append("不要自称 AI，不要写列表。");
        return sb.toString();
    }

    private String buildExplainPrompt(TeachContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("【这盘棋的情况】\n");
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

        sb.append("\n【这步棋的结论】\n");
        sb.append("学员走的是：").append(ctx.getPlayedMove()).append("。\n");
        sb.append("走之前的形势：").append(ctx.evalText(ctx.getEvalBefore())).append("。\n");
        sb.append("走之后的形势：").append(ctx.evalText(ctx.getEvalAfter())).append("。\n");
        sb.append("这步棋让学员白白损失了大约 ")
                .append(String.format("%.2f", ctx.getEvalLoss() / 100.0))
                .append(" 个兵的价值，属于「").append(ctx.getQuality()).append("」。\n");
        if (ctx.getBetterMove() != null) {
            sb.append("更好的走法是 ").append(ctx.getBetterMove());
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
        StringBuilder sb = new StringBuilder();
        sb.append("【这盘棋的情况】\n");
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
                    ? "学员现在最好走 "
                    : "现在轮到对手走，它最好的一步是 ");
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
                - 如果他问的是「我该怎么走」，就把上面给出的那一步直接告诉他，并说清思路。
                - 如果他问的和象棋无关，简单回应一句，再把话题带回这盘棋。
                """);
        return sb.toString();
    }

    // ==================== 大模型调用 ====================

    /**
     * 同步调用（一次拿全量），保留给不支持流式的调用方。
     * <p>
     * 直接复用流式的整条链路，图的是「保护逻辑只有一份」：停顿超时、整体上限、
     * 失败降级、已吐内容保留，全都不用在这里再写一遍。
     */
    private String ask(String system, String user, Supplier<String> fallback) {
        CoachStream stream = stream(system, user, fallback);
        try {
            stream.deltas().collectList().block(Duration.ofMillis(props.getStreamMaxMs()));
        } catch (Exception e) {
            log.warn("大模型调用失败，降级为本地提示：{}", e.toString());
        }
        String text = stream.text();
        return text.isEmpty() ? fallback.get() : text;
    }

    /**
     * 流式调用：把大模型吐出来的碎片一段段清洗后交出去。
     * <p>
     * 三层保护，缺一不可：
     * <ol>
     *   <li>{@code timeout} 管「两段之间最长停顿多久」—— 它从订阅那一刻就开始计时，
     *       所以首字迟迟不来也会被抓到，用户不会对着转圈干等；</li>
     *   <li>{@code take} 管整条流的时长上限，模型一直慢慢吐也不会无限拖；</li>
     *   <li>{@code onErrorResume} 兜住失败：<b>已经吐出去的内容照常保留</b>（用户都看到了，
     *       不能收回），只有「一个字都还没出去」时才降级成本地提示。</li>
     * </ol>
     */
    private CoachStream stream(String system, String user, Supplier<String> fallback) {
        if (!isAvailable()) {
            return CoachStream.local(fallback.get());
        }

        StreamingTextFilter filter = new StreamingTextFilter();
        AtomicBoolean degraded = new AtomicBoolean(false);
        AtomicBoolean produced = new AtomicBoolean(false);

        Flux<String> deltas = llm.stream(system, user)
                // 订阅（含 DNS、TLS 握手、建连）放到弹性线程池，别占着处理 HTTP 请求的容器线程
                .subscribeOn(Schedulers.boundedElastic())
                .concatMap(chunk -> justIfAny(filter.accept(chunk)))
                .concatWith(Flux.defer(() -> justIfAny(filter.finish())))
                .doOnNext(text -> produced.set(true))
                .timeout(Duration.ofMillis(props.getTimeoutMs()))
                .take(Duration.ofMillis(props.getStreamMaxMs()))
                .onErrorResume(e -> {
                    if (produced.get()) {
                        log.warn("大模型流式输出中断，已吐出的部分照常保留：{}", e.toString());
                        return Flux.empty();
                    }
                    log.warn("大模型流式调用失败，降级为本地提示：{}", e.toString());
                    degraded.set(true);
                    return Flux.just(fallback.get());
                });

        return new CoachStream(deltas, degraded);
    }

    private static Flux<String> justIfAny(String text) {
        return text == null || text.isEmpty() ? Flux.empty() : Flux.just(text);
    }

    // ==================== 本地降级文案 ====================

    private String localGreeting(TeachContext ctx) {
        return "我是棋叔。你执" + ctx.playerSideText() + "，走出好棋我夸，走出亏棋我提醒，随时可以问我。";
    }

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
