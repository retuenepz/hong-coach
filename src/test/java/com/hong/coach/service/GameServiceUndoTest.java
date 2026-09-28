package com.hong.coach.service;

import com.hong.coach.coach.CoachProperties;
import com.hong.coach.coach.CoachService;
import com.hong.coach.game.Move;
import com.hong.coach.game.PositionMove;
import com.hong.coach.game.PositionSetup;
import com.hong.coach.game.Side;
import com.hong.coach.repository.GameRepository;
import com.hong.coach.repository.entity.ChessMove;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 悔棋的契约测试。
 * <p>
 * 要钉住的是四件事：
 * <ol>
 *   <li>悔棋要把盘面、回合、棋谱三样一起退回去 —— 只改盘面不改镜头，下一手就会走错人；</li>
 *   <li>「电脑应了一手」时一次退两手，回到人落子之前；其余情况退一手；</li>
 *   <li>次数不限，一路能退到开局，再退就如实说没得悔（而不是抛异常或把棋盘搞乱）；</li>
 *   <li>已经分出胜负的棋局悔棋后回到进行中 —— 后悔了想接着下。</li>
 * </ol>
 */
class GameServiceUndoTest {

    /** 开局红炮二平五：(7,7) → (7,4) */
    private static final PositionMove RED_CANNON = position(7, 7, 7, 4);

    /** 开局黑炮2平5：(2,1) → (2,4) */
    private static final PositionMove BLACK_CANNON = position(2, 1, 2, 4);

    /**
     * 一步杀的残局：红车走 (5,0)→(0,0) 沿底线将军，另一车封住第 2 行，
     * 黑将 (0,4) 的三个逃格 (0,3)/(0,5)/(1,4) 全被封死 —— 将死。
     * <p>
     * 红帅特意放在 (9,3) 而不是 (9,4)：摆在正中会跟黑将同列照面，这个局面本身就是非法的。
     */
    private static final String MATE_IN_ONE = "4k4/8R/9/9/9/R8/9/9/9/3K5 w - - 0 1";

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

    // ==================== 退一手 ====================

    @Test
    @DisplayName("人自己走一手：悔棋退回一手，棋谱清空、回合也跟着回来")
    void undoRevertsOneManualMove() {
        store.create("u1", Side.RED);
        service.playerMove("u1", RED_CANNON);          // 红方走，轮到黑方

        Map<String, Object> resp = service.undoMove("u1");

        assertEquals("undone", resp.get("result"));
        assertEquals(1, resp.get("undoneCount"));
        assertEquals("RED", resp.get("turn"), "退完之后又轮到红方走");
        assertEquals(0, moves(resp).size(), "棋谱也得一起退干净");
        assertEquals(0, resp.get("moveCount"));

        List<ChessMove> undone = undoneOf(resp);
        assertEquals(1, undone.size());
        assertEquals("炮二平五", undone.get(0).getChinese(), "要告诉用户退的是哪一手");
        assertEquals(Side.RED, undone.get(0).getSide());
    }

    @Test
    @DisplayName("悔棋把棋子摆回原位，不只是把棋谱抹掉")
    void undoRestoresThePieceOnBoard() {
        GameSession session = store.create("u2", Side.RED);
        String startFen = session.getBoard().getFen();
        service.playerMove("u2", RED_CANNON);

        Map<String, Object> resp = service.undoMove("u2");

        List<List<String>> matrix = boardOf(resp);
        assertEquals("C", matrix.get(7).get(7), "红炮要回到 (7,7)");
        assertNull(matrix.get(7).get(4), "(7,4) 该是空的");
        assertEquals(startFen, resp.get("fen"), "悔完就该回到开局那个局面");
    }

    // ==================== 退两手 ====================

    @Test
    @DisplayName("电脑替你应过一手：一次悔棋把它的应招也退掉，直接回到你落子之前")
    void undoRevertsEngineReplyTogether() {
        when(engine.chooseMove(any(), any())).thenReturn(new Move("b7e7"));   // 黑炮2平5
        store.create("u3", Side.RED);
        service.playerMove("u3", RED_CANNON);
        service.engineMove("u3");                       // 电脑替黑方应一手，又轮到红方

        Map<String, Object> resp = service.undoMove("u3");

        assertEquals(2, resp.get("undoneCount"), "应招和它前面那一手要一起退");
        assertEquals("RED", resp.get("turn"), "回到我走之前，还是我走");
        assertEquals(0, moves(resp).size());
        assertEquals(0, resp.get("moveCount"));
    }

    @Test
    @DisplayName("连点两次「电脑走一步」：退一手就够，不去多退前一手的电脑着法")
    void consecutiveEngineMovesUndoOneAtATime() {
        when(engine.chooseMove(any(), any()))
                .thenReturn(new Move("h2e2"))           // 电脑替红方走
                .thenReturn(new Move("b7e7"));          // 电脑替黑方走
        store.create("u4", Side.RED);
        service.engineMove("u4");
        service.engineMove("u4");

        Map<String, Object> resp = service.undoMove("u4");

        assertEquals(1, resp.get("undoneCount"), "两手都是电脑走的，只退最后那一手");
        assertEquals("BLACK", resp.get("turn"), "退掉黑的，轮回黑方");
        assertEquals(1, moves(resp).size());
    }

    // ==================== 次数不限 ====================

    @Test
    @DisplayName("次数不限：一直退总能退回开局，再退就如实说没有可悔的棋")
    void undoIsUnlimitedUntilTheStart() {
        GameSession session = store.create("u5", Side.RED);
        String startFen = session.getBoard().getFen();
        service.playerMove("u5", RED_CANNON);           // 红走，轮到黑
        service.playerMove("u5", BLACK_CANNON);         // 黑也自己走，轮到红

        assertEquals(1, service.undoMove("u5").get("undoneCount"));
        Map<String, Object> last = service.undoMove("u5");
        assertEquals(1, last.get("undoneCount"));
        assertEquals(startFen, last.get("fen"), "退到头就是开局那个局面");

        Map<String, Object> extra = service.undoMove("u5");
        assertEquals("nothing_to_undo", extra.get("result"));
        assertNotNull(extra.get("message"));
        assertEquals("RED", extra.get("turn"), "没得悔也不能把棋局动坏");
        assertEquals(0, moves(extra).size());
    }

    @Test
    @DisplayName("开局第一手之前就点悔棋：如实回应，不动棋局")
    void undoBeforeAnyMoveIsReported() {
        store.create("u6", Side.RED);

        Map<String, Object> resp = service.undoMove("u6");

        assertEquals("nothing_to_undo", resp.get("result"));
        assertEquals(0, resp.get("moveCount"));
        assertEquals("RED", resp.get("turn"));
    }

    // ==================== 终局与对话 ====================

    @Test
    @DisplayName("已经将死的棋局，悔棋之后回到进行中，可以接着下")
    void undoRevivesAFinishedGame() {
        service.startFromPosition("u7", Side.RED,
                PositionSetup.build("u7", null, MATE_IN_ONE, null));
        Map<String, Object> mate = service.playerMove("u7", position(5, 0, 0, 0));
        assertEquals("game_over", mate.get("result"), "这局面该是一步杀，先确认前提成立");

        Map<String, Object> resp = service.undoMove("u7");

        assertEquals("undone", resp.get("result"));
        assertEquals(Boolean.FALSE, resp.get("gameOver"), "悔棋要能撤销终局");
        assertNull(resp.get("winner"));
        assertEquals("RED", resp.get("turn"));
    }

    @Test
    @DisplayName("悔掉电脑的着法，对话里那条「我替你走一步」也一并摘掉")
    void undoDropsTheEngineChatLine() {
        when(engine.chooseMove(any(), any())).thenReturn(new Move("b7e7"));
        GameSession session = store.create("u8", Side.RED);
        service.playerMove("u8", RED_CANNON);
        service.engineMove("u8");
        assertTrue(hasEngineLine(session), "电脑走完该留一条记录");

        service.undoMove("u8");

        assertFalse(hasEngineLine(session), "棋都悔回去了，那条记录留着只会自相矛盾");
    }

    @Test
    @DisplayName("悔棋会清掉上一步的复盘结论：讲的是一步已经不存在的棋")
    void undoClearsTheStaleReview() {
        GameSession session = store.create("u9", Side.RED);
        service.playerMove("u9", RED_CANNON);
        session.setLastReview(new MoveReview(Side.RED, "炮二平五", "h2e2", 0, -300, 300,
                "漏着", "马八进七", "b2c4", List.of()));

        service.undoMove("u9");

        assertNull(session.getLastReview());
    }

    @Test
    @DisplayName("对局不存在时如实回应")
    void unknownSessionIsReported() {
        assertEquals("no_session", service.undoMove("nope").get("result"));
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

    private static boolean hasEngineLine(GameSession session) {
        return session.getMessages().stream()
                .anyMatch(m -> "电脑".equals(m.getTag()));
    }

    @SuppressWarnings("unchecked")
    private static List<ChessMove> moves(Map<String, Object> resp) {
        return (List<ChessMove>) resp.get("moves");
    }

    @SuppressWarnings("unchecked")
    private static List<ChessMove> undoneOf(Map<String, Object> resp) {
        return (List<ChessMove>) resp.get("undone");
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> boardOf(Map<String, Object> resp) {
        return (List<List<String>>) resp.get("board");
    }
}
