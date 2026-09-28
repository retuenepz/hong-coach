package com.hong.coach.service;

import com.hong.coach.coach.CoachProperties;
import com.hong.coach.coach.CoachService;
import com.hong.coach.engine.EngineAnalysis;
import com.hong.coach.game.Move;
import com.hong.coach.game.PositionMove;
import com.hong.coach.game.Side;
import com.hong.coach.repository.GameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「电脑不自动走棋」这条规则的契约测试。
 * <p>
 * 核心是三件事：
 * <ol>
 *   <li>人手动走完一手，电脑<b>不许</b>自动应招，引擎也不该被叫起来算棋；</li>
 *   <li>人红黑两边都能走（轮到谁就能动谁），走不合法的一方照样被拒；</li>
 *   <li>点「电脑走一步」，它替<b>当前该走的那一方</b>走 —— 不看我执哪边。</li>
 * </ol>
 * 另外钉住「复盘按走子方算」：红黑都能由人来走之后，评分视角不能再看 {@code playerSide}。
 */
class GameServiceManualPlayTest {

    /** 开局红炮二平五：(7,7) → (7,4)，对应 UCI h2e2 */
    private static final PositionMove RED_CANNON = position(7, 7, 7, 4);

    /** 开局黑炮2平5：(2,1) → (2,4)，对应 UCI b7e7 */
    private static final PositionMove BLACK_CANNON = position(2, 1, 2, 4);

    private GameSessionStore store;
    private EngineAdvisor engine;
    private GameService service;

    @BeforeEach
    void setUp() {
        engine = mock(EngineAdvisor.class);
        when(engine.isAvailable()).thenReturn(true);
        when(engine.statusText()).thenReturn("可用");
        CoachService coach = mock(CoachService.class);
        when(coach.isAvailable()).thenReturn(true);
        store = new GameSessionStore(mock(GameRepository.class));
        service = new GameService(engine, coach, new CoachProperties(), store,
                new GameViewAssembler(engine, coach, store));
    }

    // ==================== 手动走子 ====================

    @Test
    @DisplayName("手动走一手之后电脑不会自动应招，引擎也不会被叫起来选步")
    void manualMoveDoesNotTriggerEngineReply() {
        store.create("m1", Side.RED);

        Map<String, Object> resp = service.playerMove("m1", RED_CANNON);

        assertEquals("ok", resp.get("result"));
        assertEquals(1, moves(resp).size(), "只该有这一手，没有应招");
        assertEquals("RED", resp.get("mover"), "这一手是红方走的");
        assertEquals("BLACK", resp.get("turn"), "回合交给黑方，等下一步指令");
        verify(engine, never()).chooseMove(any(), any());
    }

    @Test
    @DisplayName("我执红，也能接着手动走黑方：红黑两边都归用户走")
    void manualMoveWorksForBothSides() {
        GameSession session = store.create("m2", Side.RED);

        service.playerMove("m2", RED_CANNON);          // 红方走
        Map<String, Object> resp = service.playerMove("m2", BLACK_CANNON);   // 黑方也自己走

        assertEquals("ok", resp.get("result"));
        assertEquals("BLACK", resp.get("mover"));
        assertEquals(2, moves(resp).size());
        assertEquals(Side.BLACK, moves(resp).get(1).getSide(), "第二手记的是黑方");
        assertEquals("RED", resp.get("turn"), "黑方走完又轮回红方");
        assertEquals("RED", resp.get("playerSide"), "视角方不变：手动走黑方不会把我变成执黑");
        assertNotNull(session.getLastReview());
        verify(engine, never()).chooseMove(any(), any());
    }

    @Test
    @DisplayName("走不属于当前回合一方的子，照样判违规并记一笔")
    void movingTheOtherSidesPieceIsRejected() {
        store.create("m3", Side.RED);          // 轮红方走

        Map<String, Object> resp = service.playerMove("m3", BLACK_CANNON);

        assertEquals("foul", resp.get("result"));
        assertEquals(0, moves(resp).size(), "违规不能记进棋谱");
        assertEquals(1, fouls(resp).size());
    }

    // ==================== 电脑走一步 ====================

    @Test
    @DisplayName("电脑替「当前该走的一方」走：我执黑时它也会替红方出手")
    void enginePlaysForTheSideToMove() {
        when(engine.chooseMove(any(), any())).thenReturn(new Move("h2e2"));   // 红炮二平五
        store.create("m4", Side.BLACK);        // 视角方是黑方，但棋盘上轮到红方

        Map<String, Object> resp = service.engineMove("m4");

        assertEquals("engine_moved", resp.get("result"));
        assertEquals("RED", resp.get("mover"), "轮到红方，电脑就替红方走");
        assertEquals("BLACK", resp.get("turn"), "走完轮回黑方");
        assertEquals(1, moves(resp).size());
        assertEquals(Side.RED, moves(resp).get(0).getSide());
        assertTrue(moves(resp).get(0).isAi(), "这一手要标明是电脑走的");
    }

    @Test
    @DisplayName("电脑走一步之后轮回另一方，用户可以接着自己走")
    void manualAndEngineMovesCanAlternate() {
        when(engine.chooseMove(any(), any())).thenReturn(new Move("b7e7"));   // 黑炮2平5
        store.create("m5", Side.RED);

        service.playerMove("m5", RED_CANNON);            // 我走红方
        Map<String, Object> resp = service.engineMove("m5");   // 电脑替黑方应一手

        assertEquals("BLACK", resp.get("mover"));
        assertEquals("RED", resp.get("turn"));
        assertEquals(2, moves(resp).size());
        assertEquals("炮二平五", moves(resp).get(0).getChinese());
        assertEquals("炮2平5", moves(resp).get(1).getChinese());
    }

    @Test
    @DisplayName("电脑走一步会把上一步的复盘结论清掉，免得教练拿旧棋局开讲")
    void engineMoveClearsStaleReview() {
        when(engine.chooseMove(any(), any())).thenReturn(new Move("b7e7"));
        GameSession session = store.create("m6", Side.RED);
        service.playerMove("m6", RED_CANNON);
        session.setLastReview(new MoveReview(Side.RED, "炮二平五", "h2e2", 0, -300, 300,
                "漏着", "马八进七", "b2c4", List.of()));

        service.engineMove("m6");

        assertNull(session.getLastReview(), "换人走了，上一步的复盘不能再留着");
    }

    @Test
    @DisplayName("引擎未就绪时如实说走不了，不抛异常、也不记假棋谱")
    void engineUnavailableIsReported() {
        when(engine.isAvailable()).thenReturn(false);
        store.create("m7", Side.RED);

        Map<String, Object> resp = service.engineMove("m7");

        assertEquals("engine_no_move", resp.get("result"));
        assertEquals(0, moves(resp).size());
        assertNotNull(resp.get("message"));
    }

    @Test
    @DisplayName("对局不存在时如实回应")
    void unknownSessionIsReported() {
        assertEquals("no_session", service.engineMove("nope").get("result"));
    }

    // ==================== 复盘视角 ====================

    @Test
    @DisplayName("复盘按「走这一步的那一方」算分，不看 user 执哪一边")
    void reviewIsComputedFromTheMoversPerspective() {
        // 轮到谁走就给谁 0 分，轮到对方走就给对方 +3 兵 —— 也就是「谁走谁亏」的假局面。
        // 这样只要视角算对了，红黑两步都会得出 evalLoss=300。
        when(engine.analyzeForReview(anyString())).thenAnswer(invocation -> {
            String fen = invocation.getArgument(0);
            EngineAnalysis analysis = new EngineAnalysis();
            analysis.setFen(fen);
            analysis.setScoreCp(fen.split("\\s+")[1].equals("b") ? 0 : 300);
            return analysis;
        });
        store.create("m8", Side.RED);

        service.playerMove("m8", RED_CANNON);                        // 红方走，红方视角亏 3 兵
        GameSession session = store.get("m8");
        assertEquals(Side.RED, session.getLastReview().side());
        assertEquals(300, session.getLastReview().evalLoss());

        Map<String, Object> resp = service.playerMove("m8", BLACK_CANNON);   // 黑方走，黑方视角亏 3 兵
        assertEquals("BLACK", resp.get("mover"));
        assertEquals(300, resp.get("evalLoss"), "黑方走的棋就得按黑方视角算，否则符号会翻过来");
        assertEquals(Side.BLACK, session.getLastReview().side());
        assertEquals("漏着", resp.get("quality"));
        assertEquals(Boolean.TRUE, resp.get("coachTriggered"));
    }

    // ==================== 辅助 ====================

    private static PositionMove position(int fromR, int fromC, int toR, int toC) {
        PositionMove m = new PositionMove();
        m.setFromR(fromR);
        m.setFromC(fromC);
        m.setToR(toR);
        m.setToC(toC);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<com.hong.coach.repository.entity.ChessMove> moves(Map<String, Object> resp) {
        return (List<com.hong.coach.repository.entity.ChessMove>) resp.get("moves");
    }

    @SuppressWarnings("unchecked")
    private static List<com.hong.coach.repository.entity.ChessFoul> fouls(Map<String, Object> resp) {
        return (List<com.hong.coach.repository.entity.ChessFoul>) resp.get("fouls");
    }
}
