package com.hong.coach.service;

import com.hong.coach.coach.CoachProperties;
import com.hong.coach.coach.CoachService;
import com.hong.coach.game.Move;
import com.hong.coach.game.Side;
import com.hong.coach.repository.GameRepository;
import com.hong.coach.repository.entity.ChessChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 换边功能测试。
 * <p>
 * 这里的核心契约是：换边<b>只换视角</b>，一步棋都不走。改完视角之后回合还在原来那一方手上，
 * 引擎不该被惊动 —— 要不要电脑出手，是用户点「电脑走一步」的事。
 */
class GameServiceSwitchSideTest {

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

    @Test
    @DisplayName("换边只改视角：一步都不走，回合也不变")
    void switchingSideDoesNotMoveAnyPiece() {
        when(engine.chooseMove(any(), any())).thenReturn(new Move("h2e2"));
        store.create("g1", Side.RED);         // 用户执红，轮红方先行

        Map<String, Object> resp = service.switchSide("g1");

        assertEquals("switched", resp.get("result"));
        assertEquals("BLACK", resp.get("playerSide"), "换边后用户执黑");
        assertEquals("RED", resp.get("turn"), "换边不该动棋，回合仍然在红方");
        assertEquals(0, moves(resp).size(), "一步都不该走");
        verify(engine, never()).chooseMove(any(), any());
    }

    @Test
    @DisplayName("换过去再换回来，盘面与回合都没被动过")
    void switchingTwiceIsANoOpOnTheBoard() {
        GameSession session = store.create("g2", Side.BLACK);
        String fenBefore = session.getBoard().getFen();

        service.switchSide("g2");
        Map<String, Object> resp = service.switchSide("g2");

        assertEquals("BLACK", resp.get("playerSide"), "换了两次等于没换");
        assertEquals("RED", resp.get("turn"));
        assertEquals(fenBefore, session.getBoard().getFen(), "盘面必须原样");
        assertEquals(0, moves(resp).size());
        verify(engine, never()).chooseMove(any(), any());
    }

    @Test
    @DisplayName("换边会写一条对话，并把上一步的复盘结论丢掉（那是换边前那一方的评分）")
    void recordsMessageAndDropsStaleReview() {
        GameSession session = store.create("g3", Side.RED);
        session.setLastReview(new MoveReview(Side.RED, "帅五进一", "e0e1", 10, -300, 310,
                "漏着", "炮二平五", "h2e2", List.of()));

        Map<String, Object> resp = service.switchSide("g3");

        assertNull(session.getLastReview(), "换边后旧的复盘结论必须作废");
        List<ChessChatMessage> chat = messages(resp);
        assertFalse(chat.isEmpty());
        assertEquals("换边", chat.get(chat.size() - 1).getTag());
        assertEquals(ChessChatMessage.ROLE_COACH, chat.get(chat.size() - 1).getRole());
    }

    @Test
    @DisplayName("对局不存在时如实回应，不抛异常")
    void unknownSessionIsReported() {
        Map<String, Object> resp = service.switchSide("nope");

        assertEquals("no_session", resp.get("result"));
    }

    // ==================== 辅助 ====================

    @SuppressWarnings("unchecked")
    private static List<com.hong.coach.repository.entity.ChessMove> moves(Map<String, Object> resp) {
        return (List<com.hong.coach.repository.entity.ChessMove>) resp.get("moves");
    }

    @SuppressWarnings("unchecked")
    private static List<ChessChatMessage> messages(Map<String, Object> resp) {
        return (List<ChessChatMessage>) resp.get("chat");
    }
}
