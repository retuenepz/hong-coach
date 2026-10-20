package com.hong.coach.service;

import com.hong.coach.coach.CoachProperties;
import com.hong.coach.coach.CoachService;
import com.hong.coach.coach.CoachStream;
import com.hong.coach.coach.TeachContext;
import com.hong.coach.engine.EngineAnalysis;
import com.hong.coach.game.Board;
import com.hong.coach.game.ChineseNotation;
import com.hong.coach.game.FenParser;
import com.hong.coach.game.Move;
import com.hong.coach.game.PositionMove;
import com.hong.coach.game.PositionSetup;
import com.hong.coach.game.Side;
import com.hong.coach.game.XqRules.Piece;
import com.hong.coach.game.XqRules.Pos;
import com.hong.coach.repository.entity.ChessChatMessage;
import com.hong.coach.repository.entity.ChessMove;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 对局编排：把「走子 → 引擎复盘 → 教练点评」串成一条链路。
 * <p>
 * <b>谁走哪一方</b>：这个服务不区分「人」和「电脑」的阵营 —— 轮到谁走，谁就可以走。
 * 人可以手动走任意一方的棋子（适合自己跟自己摆变化、复演名局），
 * 也可以随时点「电脑走一步」让引擎替<b>当前该走的那一方</b>出一手。
 * {@code playerSide} 只是「用户站在哪一边看」的视角方，用于棋盘朝向与教练的称呼，
 * 不再限制用户能走哪些棋子；真正的约束在 {@link Board#isLegal} —— 只能动轮到的那一方。
 * <p>
 * 引擎不会被自动触发，只在两种情况下被问：
 * <ol>
 *   <li>{@link #playerMove} 走子前后各分析一次，算出这一步的评分落差；</li>
 *   <li>{@link #engineMove} 用户点「电脑走一步」时替当前一方选一步。</li>
 * </ol>
 * 这个类只做编排，具体能力都委托出去：引擎在 {@link EngineAdvisor}，
 * 会话存取与落库在 {@link GameSessionStore}，出参组装在 {@link GameViewAssembler}。
 */
@Slf4j
@Service
public class GameService {

    /** 电脑走棋在对话里留的标签。悔棋时要按它把对应的那几条摘掉，否则盘上没这步棋、对话里还留着。 */
    private static final String TAG_ENGINE = "电脑";

    private final EngineAdvisor engine;
    private final CoachService coach;
    private final CoachProperties coachProps;
    private final GameSessionStore store;
    private final GameViewAssembler view;

    public GameService(EngineAdvisor engine,
                       CoachService coach,
                       CoachProperties coachProps,
                       GameSessionStore store,
                       GameViewAssembler view) {
        this.engine = engine;
        this.coach = coach;
        this.coachProps = coachProps;
        this.store = store;
        this.view = view;
    }

    // ==================== 开局 ====================

    /**
     * 开新局。
     * <p>
     * 谁都不自动走棋：执黑开局时棋盘上仍然轮到红方，等用户点棋子走、或者点「电脑走一步」。
     * <p>
     * 开场白<b>不在这里生成</b>：那是一句要问大模型的话，同步等它会让「开新局」按钮卡上十几秒。
     * 响应照常秒回，前端随后用 {@link #greetingStream} 把开场白一边生成一边显示出来。
     */
    public Map<String, Object> newGame(String id, Side playerSide) {
        GameSession session = store.create(id, playerSide);
        session.touch();
        store.persist(session);

        Map<String, Object> resp = view.state(session);
        resp.put("result", "new");
        return resp;
    }

    // ==================== 换边 ====================

    /**
     * 换视角：把「用户站在哪一边」换到另一方，棋盘朝向也跟着翻。
     * <p>
     * 棋局本身完全不动 —— 一步都不走，回合还是原来的回合。换边只是换个看棋的位置：
     * 教练从此按新的一方称呼「你」。
     * <p>
     * 顺带把上一步的复盘结论丢掉：那份结论是站在换边前那一方的视角算的，留着只会误导教练。
     */
    public Map<String, Object> switchSide(String id) {
        GameSession session = store.get(id);
        if (session == null) return view.noSession(id);
        synchronized (session) {
            Side previous = session.getPlayerSide();
            session.setPlayerSide(previous.opponent());
            session.invalidateCache();
            session.setLastReview(null);

            session.addMessage(ChessChatMessage.coach(
                    "换边了，现在你执" + sideName(session.getPlayerSide()) + "。", "换边"));
            session.touch();
            store.persist(session);

            Map<String, Object> resp = view.state(session);
            resp.put("result", "switched");
            resp.put("switchedFrom", previous.name());
            return resp;
        }
    }

    // ==================== 摆棋 ====================

    /**
     * 只校验用户摆的局面，不开局。
     * <p>
     * 前端在摆棋页里边摆边调，好让用户及时看到「这儿不对」，而不是点了开始才被拒。
     */
    public Map<String, Object> checkPosition(PositionSetup.Built built) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("result", "check");
        resp.put("ok", built.ok());
        resp.put("problems", built.problems());
        resp.put("message", built.problems().isEmpty() ? null : built.problems().get(0));
        // 出错的格子：界面拿它把棋盘上不对的地方圈出来
        resp.put("badCells", badCells(built.details()));
        Board board = built.board();
        if (board != null) {
            resp.put("matrix", board.toMatrix());
            resp.put("fen", board.getFen());
            resp.put("turn", board.getTurn().name());
        }
        return resp;
    }

    /**
     * 落子之前的判断：这一枚子放得下去吗。
     * <p>
     * 摆棋页在鼠标悬停、以及真正落子的那一刻都会问它。问在<b>落子之前</b>，
     * 用户就不会先看到子摆上去了、再被一串报错赶着去挪 —— 放不下去的摆放直接不进盘面。
     */
    public Map<String, Object> checkPlacement(PositionSetup.Placement placement) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("result", "place");
        resp.put("ok", placement.ok());
        resp.put("message", placement.message());
        resp.put("badCells", badCells(placement.details()));
        return resp;
    }

    /** 把「带位置的问题」摊平成一个个格子，同一个格子只报一次 */
    private List<Map<String, Object>> badCells(List<FenParser.Problem> details) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FenParser.Problem problem : details) {
            for (Pos pos : problem.cells()) {
                if (!seen.add(pos.r + "," + pos.c)) continue;
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("r", pos.r);
                cell.put("c", pos.c);
                cell.put("text", problem.text());
                out.add(cell);
            }
        }
        return out;
    }

    /**
     * 用用户摆好的局面开一局。
     * <p>
     * 和「选边开新局」的区别只在于棋盘从哪儿来：这里直接把校验过的盘面交给会话，
     * 并记下它作为 {@code initialFen}，读档时才摆得回来。开局后不自动走棋 ——
     * 局面轮到哪一方，就由用户自己走或点「电脑走一步」。
     * <p>
     * 开场白同样交给 {@link #greetingStream} 流式生成，见 {@link #newGame}。
     */
    public Map<String, Object> startFromPosition(String id, Side playerSide, PositionSetup.Built built) {
        if (!built.ok()) {
            Map<String, Object> resp = checkPosition(built);
            resp.put("result", "invalid");
            return resp;
        }

        GameSession session = store.create(id, built.board(), playerSide);
        session.touch();
        store.persist(session);

        Map<String, Object> resp = view.state(session);
        resp.put("result", "setup");
        return resp;
    }

    // ==================== 人走子 / 电脑走子 ====================

    /**
     * 人手动走一步棋。
     * <p>
     * 走哪一方不限 —— 轮到哪一方就动哪一方，红黑都能走（想自己摆变化、复演名局都行）。
     * 走完<b>不会</b>自动触发电脑应招，也不会额外问引擎；要不要电脑出手由用户点按钮决定。
     */
    public Map<String, Object> playerMove(String id, PositionMove positionMove) {
        GameSession session = store.get(id);
        if (session == null) return view.noSession(id);
        // 同一局可能被并发请求（双击、断线重发），整段走子过程串行化
        synchronized (session) {
            return doPlayerMove(session, positionMove);
        }
    }

    private Map<String, Object> doPlayerMove(GameSession session, PositionMove positionMove) {
        Board board = session.getBoard();

        if (board.isGameOver()) {
            Map<String, Object> resp = view.state(session);
            resp.put("result", "game_over");
            return resp;
        }

        Move move = parseMove(positionMove);
        if (move == null) {
            Map<String, Object> resp = view.state(session);
            resp.put("result", "foul");
            resp.put("message", "走法格式不正确");
            return resp;
        }
        if (!board.isLegal(move)) {
            // 拿到棋子归属的人不用再自己判断：isLegal 已经要求「只能动轮到的那一方的子」
            session.addFoul(describeFoul(board, move));
            store.persist(session);
            Map<String, Object> resp = view.state(session);
            resp.put("result", "foul");
            resp.put("message", "这一步不合法，请重新选择");
            return resp;
        }

        // 走子方 = 当前回合方；必须在下子之前记下来，下完就变了
        Side mover = board.getTurn();

        // --- 1. 落子之前：中文记谱必须在盘面还没变的时候算，否则会记出错误的着法 ---
        EngineAnalysis before = engine.analyzeForReview(board.getFen());
        String playedChinese = ChineseNotation.toChinese(board, move);
        String betterChinese = before == null ? null
                : ChineseNotation.toChineseOrUci(board, before.getBestMove());

        boolean captured = board.at(move.to.r, move.to.c) != null;
        if (!board.makeMove(move)) {
            // 走到这里说明校验和落子之间盘面变了（并发请求），按违规处理，绝不记一笔假棋谱
            log.warn("落子失败，盘面可能在校验后被改动 session={} move={}", session.getId(), move);
            session.addFoul(describeFoul(board, move));
            store.persist(session);
            Map<String, Object> resp = view.state(session);
            resp.put("result", "foul");
            resp.put("message", "这一步没能落子，请重新选择");
            return resp;
        }
        ChessMove played = new ChessMove(session.nextMoveNo(), mover,
                move.coordToUci(), playedChinese, captured);
        session.addMove(played);
        session.invalidateCache();
        session.touch();

        // 这一手直接把对手将死/困毙
        if (board.isGameOver()) {
            store.persist(session);
            Map<String, Object> resp = view.state(session);
            resp.put("result", "game_over");
            resp.put("playerMove", played);
            return resp;
        }

        // --- 2. 落子之后：拿到新的评分，和走之前的一比就是这一步的落差 ---
        EngineAnalysis after = engine.analyzeForReview(board.getFen());
        MoveReview review = reviewOf(mover, board, played, before, after, betterChinese);
        session.setLastReview(review);

        store.persist(session);

        Map<String, Object> resp = view.state(session);
        resp.put("result", "ok");
        resp.put("playerMove", played);
        resp.put("mover", mover.name());
        resp.put("evalBefore", review.evalBefore());
        resp.put("evalAfter", review.evalAfter());
        resp.put("evalLoss", review.evalLoss());
        resp.put("quality", review.quality());
        resp.put("betterMove", review.betterChinese());
        resp.put("coachTriggered", shouldExplain(review));
        return resp;
    }

    /**
     * 让电脑替<b>当前该走的那一方</b>出一手。
     * <p>
     * 哪一方该走就走哪一方：轮红方走，它替红方走；轮黑方走，它替黑方走。
     * 这一手不产生复盘结论（把上一步的结论清掉），因为「引擎自己走了一手、再让教练点评自己」
     * 没有教学意义；要学东西，用旁边那步自己走的棋。
     */
    public Map<String, Object> engineMove(String id) {
        GameSession session = store.get(id);
        if (session == null) return view.noSession(id);
        synchronized (session) {
            Board board = session.getBoard();
            if (board.isGameOver()) {
                Map<String, Object> resp = view.state(session);
                resp.put("result", "game_over");
                return resp;
            }

            Side mover = board.getTurn();
            ChessMove played = engineMoveFor(session, mover);
            if (played == null) {
                Map<String, Object> resp = view.state(session);
                resp.put("result", "engine_no_move");
                resp.put("message", "引擎没能给出这一步（引擎可能未就绪，或这一方已无棋可走）");
                return resp;
            }

            session.setLastReview(null);
            session.addMessage(ChessChatMessage.coach(
                    "我替" + sideName(mover) + "走一步：" + played.getChinese() + "。", TAG_ENGINE));
            session.touch();
            store.persist(session);

            Map<String, Object> resp = view.state(session);
            resp.put("result", "engine_moved");
            resp.put("aiMove", played);
            resp.put("mover", mover.name());
            return resp;
        }
    }

    // ==================== 悔棋 ====================

    /**
     * 悔棋：把最近的着法退回盘面。次数不限，一直能退到这一局的开局局面。
     * <p>
     * <b>退几手由这里定</b>，前端不参与判断：最后一步是电脑走的、它前面一手却是人走的，
     * 那正是「我走一步、又点电脑走了一步」的情形，两手一起退 —— 悔棋的直觉就是回到
     * 我上一步落子之前。其余情况（人自己走了红又走黑、连点两次电脑走一步）只退一手。
     * <p>
     * 退完还要把自己带出来的东西一并收拾干净：上一步的复盘结论讲的是一步已经不存在的棋，
     * 局面分析缓存对应的是旧盘面，电脑那手在对话里留的「我替你走一步」也得摘掉。
     * 已经分出胜负的棋局悔棋后会重新回到进行中 —— 后悔了想接着下，是悔棋的应有之义。
     *
     * @return 退成功的 {@code result=undone}，附带 {@code undone}（被撤的着法，按原先后）与
     *         {@code undoneCount}；已经退到开局了则为 {@code result=nothing_to_undo}
     */
    public Map<String, Object> undoMove(String id) {
        GameSession session = store.get(id);
        if (session == null) return view.noSession(id);
        // 悔棋要同时改盘面和棋谱，跟走子一样必须串行，否则两个请求能退成错位的局面
        synchronized (session) {
            List<ChessMove> undone = undoTo(session);
            if (undone.isEmpty()) {
                Map<String, Object> resp = view.state(session);
                resp.put("result", "nothing_to_undo");
                resp.put("message", "已经退到开局了，没有可悔的棋");
                return resp;
            }

            session.setLastReview(null);
            session.invalidateCache();
            session.touch();
            store.persist(session);

            Map<String, Object> resp = view.state(session);
            resp.put("result", "undone");
            resp.put("undone", undone);
            resp.put("undoneCount", undone.size());
            resp.put("message", describeUndone(undone));
            return resp;
        }
    }

    /**
     * 真正往回退，返回被撤掉的着法（按原来的先后顺序）。
     * <p>
     * 「该退两手还是一手」必须在动手之前判：退掉一手之后栈顶就换了人，
     * 那时候再想问「刚才那手是不是应招」已经没有依据了。
     */
    private List<ChessMove> undoTo(GameSession session) {
        List<ChessMove> moves = session.getMoves();
        List<ChessMove> undone = new ArrayList<>();
        if (moves.isEmpty()) return undone;

        int steps = isEngineReply(moves) ? 2 : 1;
        for (int i = 0; i < steps && !moves.isEmpty(); i++) {
            session.getBoard().undoLastMove();
            undone.add(0, moves.remove(moves.size() - 1));
        }
        dropEngineReplies(session, undone);
        return undone;
    }

    /** 末两手是不是「人走一手 + 电脑应一手」 */
    private static boolean isEngineReply(List<ChessMove> moves) {
        int n = moves.size();
        return n >= 2 && moves.get(n - 1).isAi() && !moves.get(n - 2).isAi();
    }

    /**
     * 被撤掉的电脑着法，对应地把对话里那条「我替你走一步」也摘掉。
     * <p>
     * 只从末尾往前摘连续的那几条：中间夹着的用户提问一律不动，
     * 免得悔一步棋把聊过的内容也一起抹了。
     */
    private static void dropEngineReplies(GameSession session, List<ChessMove> undone) {
        long remaining = undone.stream().filter(ChessMove::isAi).count();
        List<ChessChatMessage> messages = session.getMessages();
        while (remaining > 0 && !messages.isEmpty()
                && TAG_ENGINE.equals(messages.get(messages.size() - 1).getTag())) {
            messages.remove(messages.size() - 1);
            remaining--;
        }
    }

    /** 悔棋结果说清楚退了哪几手，前端直接拿这句话提示用户 */
    private static String describeUndone(List<ChessMove> undone) {
        List<String> parts = new ArrayList<>();
        for (ChessMove m : undone) {
            parts.add(sideName(m.getSide()) + " " + m.getChinese());
        }
        return "已退回 " + undone.size() + " 手：" + String.join("、", parts);
    }

    // ==================== AI 教练 ====================

    /**
     * 开局致辞（流式）。页面实际走这条 —— 开场白由大模型现编，
     * 若在开局响应里同步等它，「开新局」按钮就会卡住不动。
     * <p>
     * 幂等：已经有开场白（标签是「开局」的那条）就不再生成，重复调用只会把现有对话回给前端。
     */
    public CoachStreamHandle greetingStream(String id) {
        GameSession session = store.get(id);
        if (session == null) return CoachStreamHandle.noSession();

        CoachStream stream;
        synchronized (session) {
            if (hasGreeting(session)) {
                return new CoachStreamHandle(Flux.empty(), () -> chatOnly(session));
            }
            stream = coach.streamGreeting(buildContext(session));
        }

        return new CoachStreamHandle(stream.deltas(), () -> {
            GameSession latest = store.get(id);
            if (latest == null) return chatOnly(null);
            synchronized (latest) {
                // 生成期间可能已经有人补齐了开场白（并发重复请求），再确认一次
                if (!hasGreeting(latest) && !stream.text().isBlank()) {
                    latest.addMessage(ChessChatMessage.coach(stream.text(), "开局"));
                }
                latest.touch();
                store.persist(latest);
                Map<String, Object> payload = chatOnly(latest);
                payload.put("result", "greeting");
                return payload;
            }
        });
    }

    /**
     * 上一步亏棋的点评（流式）。
     */
    public CoachStreamHandle coachStream(String id) {
        GameSession session = store.get(id);
        if (session == null) return CoachStreamHandle.noSession();

        MoveReview review;
        CoachStream stream;
        synchronized (session) {
            review = session.getLastReview();
            if (review == null || review.quality() == null) {
                return new CoachStreamHandle(Flux.empty(), () -> {
                    Map<String, Object> payload = chatOnly(session);
                    payload.put("result", "nothing_to_say");
                    return payload;
                });
            }
            stream = coach.streamExplain(explainContext(session, review));
        }

        return new CoachStreamHandle(stream.deltas(), () -> {
            GameSession latest = store.get(id);
            if (latest == null) return chatOnly(null);
            synchronized (latest) {
                String comment = stream.text();
                if (!comment.isBlank()) {
                    // 降级来的本地提示标「提示」，真讲解标「等级 · 建议 xx」，
                    // 前端据此一眼看出这段是不是大模型说的
                    latest.addMessage(ChessChatMessage.coach(
                            comment, stream.isDegraded() ? "提示" : tagOf(review)));
                }
                latest.touch();
                store.persist(latest);

                Map<String, Object> payload = chatOnly(latest);
                payload.put("result", "coach");
                payload.put("comment", comment);
                payload.put("playedMove", review.playedChinese());
                payload.put("betterMove", review.betterChinese());
                payload.put("quality", review.quality());
                payload.put("evalLoss", review.evalLoss());
                return payload;
            }
        });
    }

    /**
     * 用户提问（流式）。
     * <p>
     * 提问本身先落库，这样即使用户在等回答时刷了页面，问题也不会丢。
     */
    public CoachStreamHandle chatStream(String id, String question) {
        GameSession session = store.get(id);
        if (session == null) return CoachStreamHandle.noSession();

        TeachContext ctx;
        synchronized (session) {
            session.addMessage(ChessChatMessage.user(question));

            EngineAnalysis analysis = analysisForPosition(session);
            ctx = buildContext(session);
            ctx.setCurrentEvalCp(analysis == null ? 0 : analysis.evalCpFor(session.getPlayerSide()));
            ctx.setPlayerInCheck(session.getBoard().inCheck(session.getPlayerSide()));
            if (analysis != null) {
                fillSuggestedMove(ctx, session.getBoard(), analysis);
            }
            session.touch();
            store.persist(session);
        }

        CoachStream stream = coach.streamAnswer(question, ctx);
        return new CoachStreamHandle(stream.deltas(), () -> {
            GameSession latest = store.get(id);
            if (latest == null) return chatOnly(null);
            synchronized (latest) {
                String answer = stream.text();
                if (!answer.isBlank()) {
                    latest.addMessage(ChessChatMessage.coach(answer, stream.isDegraded() ? "提示" : null));
                }
                latest.touch();
                store.persist(latest);

                Map<String, Object> payload = chatOnly(latest);
                payload.put("result", "chat");
                payload.put("answer", answer);
                payload.put("currentEval", ctx.getCurrentEvalCp());
                payload.put("suggestedMove", ctx.getSuggestedMove());
                payload.put("engineAvailable", engine.isAvailable());
                payload.put("coachAvailable", coach.isAvailable());
                return payload;
            }
        });
    }

    /** 会话里是否已经有开场白 */
    private static boolean hasGreeting(GameSession session) {
        return session.getMessages().stream().anyMatch(m -> "开局".equals(m.getTag()));
    }

    /** 流式收尾时统一给前端的对话记录；会话没了就给空表，前端看到 no_session 会重新开局 */
    private static Map<String, Object> chatOnly(GameSession session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (session == null) {
            payload.put("result", "no_session");
            payload.put("chat", List.of());
            return payload;
        }
        payload.put("chat", session.getMessages());
        return payload;
    }

    /**
     * 取「上一步亏棋」的教练点评。
     * <p>
     * 与走子分开成一个接口，是为了让前端不必等大模型：先拿到盘面立刻刷新，
     * 再异步取讲解，模型慢也不会卡住棋盘。
     */
    public Map<String, Object> coachComment(String id) {
        GameSession session = store.get(id);
        if (session == null) return view.noSession(id);
        synchronized (session) {
            MoveReview review = session.getLastReview();
            Map<String, Object> resp = new LinkedHashMap<>();
            if (review == null || review.quality() == null) {
                resp.put("result", "nothing_to_say");
                return resp;
            }
            String comment = coach.explainWeakMove(explainContext(session, review));
            session.addMessage(ChessChatMessage.coach(comment, tagOf(review)));
            session.touch();
            store.persist(session);

            resp.put("result", "coach");
            resp.put("comment", comment);
            resp.put("playedMove", review.playedChinese());
            resp.put("betterMove", review.betterChinese());
            resp.put("quality", review.quality());
            resp.put("evalLoss", review.evalLoss());
            resp.put("chat", session.getMessages());
            return resp;
        }
    }

    /**
     * 用户主动提问，教练结合盘面与引擎分析作答。
     */
    public Map<String, Object> chat(String id, String question) {
        GameSession session = store.get(id);
        if (session == null) return view.noSession(id);
        synchronized (session) {
            session.addMessage(ChessChatMessage.user(question));

            EngineAnalysis analysis = analysisForPosition(session);
            TeachContext ctx = buildContext(session);
            ctx.setCurrentEvalCp(analysis == null ? 0 : analysis.evalCpFor(session.getPlayerSide()));
            ctx.setPlayerInCheck(session.getBoard().inCheck(session.getPlayerSide()));
            if (analysis != null) {
                fillSuggestedMove(ctx, session.getBoard(), analysis);
            }

            String answer = coach.answer(question, ctx);
            session.addMessage(ChessChatMessage.coach(answer, null));
            session.touch();
            store.persist(session);

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("result", "chat");
            resp.put("answer", answer);
            resp.put("currentEval", ctx.getCurrentEvalCp());
            resp.put("suggestedMove", ctx.getSuggestedMove());
            resp.put("chat", session.getMessages());
            resp.put("engineAvailable", engine.isAvailable());
            resp.put("coachAvailable", coach.isAvailable());
            return resp;
        }
    }

    // ==================== 查询 ====================

    /**
     * 某个位置上的棋子能走到哪里，供前端高亮。
     * <p>
     * 只对<b>轮到的这一方</b>的棋子给落点：轮到谁就只能动谁。红黑不拘 ——
     * 人两边都能走，所以这里不看 {@code playerSide}。
     */
    public Map<String, Object> legalMoves(String id, int r, int c) {
        GameSession session = store.get(id);
        if (session == null) return view.noSession(id);

        Board board = session.getBoard();
        List<Map<String, Object>> targets = new ArrayList<>();
        Piece piece = board.at(r, c);
        if (piece != null && !board.isGameOver() && piece.side == board.getTurn()) {
            for (Move m : board.legalMovesAt(new Pos(r, c))) {
                Map<String, Object> target = new LinkedHashMap<>();
                target.put("r", m.to.r);
                target.put("c", m.to.c);
                target.put("uci", m.coordToUci());
                targets.add(target);
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("result", "legal");
        resp.put("r", r);
        resp.put("c", c);
        resp.put("targets", targets);
        return resp;
    }

    /**
     * 当前对局状态。前端刷新页面时先问这个接口，有对局就接着下，没有才弹开局选边。
     */
    public Map<String, Object> state(String id) {
        GameSession session = store.get(id);
        if (session == null) return view.noSession(id);
        Map<String, Object> resp = view.state(session);
        resp.put("result", "state");
        return resp;
    }

    public Map<String, Object> systemStatus() {
        return view.systemStatus();
    }

    // ==================== 内部：电脑走子 ====================

    /**
     * 让引擎替 {@code side} 走一步，并记进棋谱。棋局已结束、或引擎给不出着法时返回 null（不产生记录）。
     *
     * @param side 要替哪一方走；调用方负责传当前该走的一方，传错会被这里挡下
     */
    private ChessMove engineMoveFor(GameSession session, Side side) {
        Board board = session.getBoard();
        if (board.isGameOver() || board.getTurn() != side) return null;
        if (!engine.isAvailable()) {
            log.warn("引擎未就绪，无法替 {} 走棋 session={}", side, session.getId());
            return null;
        }

        EngineAnalysis analysis = engine.analyzeForPlaying(board.getFen());
        Move move = engine.chooseMove(board, analysis);
        if (move == null) {
            log.warn("电脑找不到可走的着法 session={} side={}", session.getId(), side);
            return null;
        }

        String chinese = ChineseNotation.toChinese(board, move);
        boolean captured = board.at(move.to.r, move.to.c) != null;
        board.makeMove(move);

        ChessMove record = new ChessMove(session.nextMoveNo(), side, move.coordToUci(), chinese, captured);
        record.setAi(true);
        session.addMove(record);
        session.invalidateCache();
        return record;
    }

    // ==================== 内部：复盘与上下文 ====================

    /**
     * 把两次分析拼成一份复盘结论。走子前后的评分都换算成<b>走子方</b>视角再相减，
     * 否则红黑视角一混就会出现「走了臭棋评分反而变好」这种荒唐结果。
     */
    private MoveReview reviewOf(Side mover, Board board, ChessMove played,
                                EngineAnalysis before, EngineAnalysis after, String betterChinese) {
        int evalBefore = before == null ? 0 : before.evalCpFor(mover);
        int evalAfter = after == null ? 0 : after.evalCpFor(mover);
        int evalLoss = evalBefore - evalAfter;
        return new MoveReview(
                mover,
                played.getChinese(),
                played.getUci(),
                evalBefore,
                evalAfter,
                evalLoss,
                MoveReview.qualityOf(evalLoss),
                betterChinese,
                before == null ? null : before.getBestMove(),
                board.describeSequence(after == null ? null : after.principalVariation()));
    }

    private boolean shouldExplain(MoveReview review) {
        return coachProps.isEnabled()
                && review.quality() != null
                && review.evalLoss() >= coachProps.getTriggerThreshold();
    }

    /**
     * 当前局面的引擎分析，尽量复用缓存：同一个局面用户连问几次，不该反复让引擎算。
     * <p>
     * 只要棋局没结束就算 —— 红黑两边都可能轮到，教练得答得出任何一方的局面。
     */
    private EngineAnalysis analysisForPosition(GameSession session) {
        if (session.getBoard().isGameOver()) return null;
        String fen = session.getBoard().getFen();
        EngineAnalysis cached = session.cachedAnalysisFor(fen);
        if (cached != null) return cached;
        EngineAnalysis analysis = engine.analyzeForReview(fen);
        session.cacheAnalysis(fen, analysis);
        return analysis;
    }

    /**
     * 组装大模型需要的上下文
     */
    private TeachContext buildContext(GameSession session) {
        Board board = session.getBoard();
        TeachContext ctx = new TeachContext();
        ctx.setPlayerSide(session.getPlayerSide());
        ctx.setFen(board.getFen());
        ctx.setBoardDiagram(board.toTextDiagram());
        ctx.setTurn(board.getTurn());
        ctx.setGameOver(board.isGameOver());
        ctx.setResultText(session.resultDetail());
        ctx.setPhase(phaseOf(board));
        ctx.setMoveList(moveListOf(session));
        return ctx;
    }

    /**
     * 主动讲解用的上下文：在基础上下文上补上「刚才那一步」的复盘细节
     * <p>
     * 这里要把视角方改成<b>刚才走子的那一方</b>：红黑都能由人来走，
     * 复盘里的评分（evalBefore / evalAfter）本来就是按走子方算的，
     * 教练必须跟着说「你亏了」才对得上，否则就会出现「黑方走的棋，教练按红方视角点评」。
     */
    private TeachContext explainContext(GameSession session, MoveReview review) {
        TeachContext ctx = buildContext(session);
        ctx.setPlayerSide(review.side());
        ctx.setPlayedMove(review.playedChinese());
        ctx.setPlayedMoveUci(review.playedUci());
        ctx.setEvalBefore(review.evalBefore());
        ctx.setEvalAfter(review.evalAfter());
        ctx.setEvalLoss(review.evalLoss());
        ctx.setQuality(review.quality());
        ctx.setBetterMove(review.betterChinese());
        ctx.setBetterMoveUci(review.betterUci());
        ctx.setPvChinese(review.pvChinese());
        ctx.setPlayerInCheck(session.getBoard().inCheck(review.side()));
        return ctx;
    }

    private void fillSuggestedMove(TeachContext ctx, Board board, EngineAnalysis analysis) {
        ctx.setSuggestedMoveUci(analysis.getBestMove());
        ctx.setSuggestedMove(ChineseNotation.toChineseOrUci(board, analysis.getBestMove()));
        ctx.setSuggestedPvChinese(board.describeSequence(analysis.principalVariation()));
    }

    /**
     * 棋谱。每一步都要标明走子方，否则大模型会把红黑的着法张冠李戴
     * （之前就出现过把「红方帅五进一」当成黑方着法的情况）。
     */
    private List<String> moveListOf(GameSession session) {
        List<String> moves = new ArrayList<>();
        for (ChessMove m : session.getMoves()) {
            moves.add((m.getSide() == Side.RED ? "红方 " : "黑方 ") + m.getChinese());
        }
        return moves;
    }

    private static String phaseOf(Board board) {
        if (board.moveCount() < 16) return "开局";
        if (board.pieceCount() <= 12) return "残局";
        return "中局";
    }

    private static String tagOf(MoveReview review) {
        if (review.betterChinese() == null) return review.quality();
        return review.quality() + " · 建议 " + review.betterChinese();
    }

    private static Move parseMove(PositionMove positionMove) {
        if (positionMove == null || positionMove.getFromR() == null || positionMove.getFromC() == null
                || positionMove.getToR() == null || positionMove.getToC() == null) {
            return null;
        }
        try {
            return new Move(positionMove);
        } catch (Exception e) {
            return null;
        }
    }

    private static String describeFoul(Board board, Move move) {
        Piece piece = board.at(move.from.r, move.from.c);
        String name = piece == null ? "空位" : piece.type.name();
        return String.format("第 %d 手：%s (%d,%d) → (%d,%d)",
                board.moveCount() + 1, name, move.from.r, move.from.c, move.to.r, move.to.c);
    }

    private static String sideName(Side side) {
        return side == Side.RED ? "红方" : "黑方";
    }
}
