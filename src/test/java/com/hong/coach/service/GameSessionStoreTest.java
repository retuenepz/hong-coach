package com.hong.coach.service;

import com.hong.coach.game.Board;
import com.hong.coach.game.ChineseNotation;
import com.hong.coach.game.FenParser;
import com.hong.coach.game.Move;
import com.hong.coach.game.Side;
import com.hong.coach.repository.GameRepository;
import com.hong.coach.repository.entity.ChessChatMessage;
import com.hong.coach.repository.entity.ChessFoul;
import com.hong.coach.repository.entity.ChessGame;
import com.hong.coach.repository.entity.ChessMove;
import com.hong.coach.repository.mapper.ChessChatMessageMapper;
import com.hong.coach.repository.mapper.ChessFoulMapper;
import com.hong.coach.repository.mapper.ChessGameMapper;
import com.hong.coach.repository.mapper.ChessMoveMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话仓库的存档 / 读档测试。
 * <p>
 * 用 mock 的 Mapper 当「数据库」：先让仓库写一遍，把写进去的东西抓出来再喂给一个
 * 全新的仓库去读，验证「存下去什么、读回来就是什么」。这样既覆盖了真实的
 * 保存→重放路径，又不需要真的连 MySQL。
 */
class GameSessionStoreTest {

    private ChessGameMapper gameMapper;
    private ChessMoveMapper moveMapper;
    private ChessFoulMapper foulMapper;
    private ChessChatMessageMapper chatMapper;
    private GameRepository repository;

    @BeforeEach
    void setUp() {
        gameMapper = mock(ChessGameMapper.class);
        moveMapper = mock(ChessMoveMapper.class);
        foulMapper = mock(ChessFoulMapper.class);
        chatMapper = mock(ChessChatMessageMapper.class);
        repository = new GameRepository(gameMapper, moveMapper, foulMapper, chatMapper);
    }

    @Test
    @DisplayName("存下来再读回去，盘面、棋谱、违规、对话全部一致")
    void saveThenRestore() {
        GameSession original = newSession("g-test");
        original.setPlayerSide(Side.BLACK);
        play(original, "h2e2");   // 红 炮二平五
        play(original, "h9g7");   // 黑 马8进7
        play(original, "b0c2");   // 红 马八进七
        original.addFoul("第 4 手：ROOK (9,0) → (4,0)");
        original.addMessage(ChessChatMessage.coach("开局我先走，炮二平五。", "对手"));
        original.addMessage(ChessChatMessage.user("我这马该跳上去吗"));
        original.addMessage(ChessChatMessage.coach("可以，马八进七先活右翼。", null));

        String expectedFen = original.getBoard().getFen();
        store().persist(original);

        ChessGame storedRow = captureHeader();
        List<ChessMove> storedMoves = captureMoves();
        List<ChessFoul> storedFouls = captureFouls();
        List<ChessChatMessage> storedMessages = captureMessages();

        assertEquals(3, storedMoves.size());
        assertEquals(3, storedRow.getMoveCount());
        assertEquals("PLAYING", storedRow.getStatus());
        assertEquals(expectedFen, storedRow.getFen(), "存下来的 FEN 就是当时的局面");
        assertEquals(new Board("x").getFen(), storedRow.getInitialFen(), "标准开局的初始局面就是满盘");

        // 换一个全新的仓库，内存里什么都没有，只能靠读档
        when(gameMapper.selectById("g-test")).thenReturn(storedRow);
        when(moveMapper.selectList(any())).thenReturn(storedMoves);
        when(foulMapper.selectList(any())).thenReturn(storedFouls);
        when(chatMapper.selectList(any())).thenReturn(storedMessages);

        GameSession restored = new GameSessionStore(repository).get("g-test");

        assertNotNull(restored, "应当能从数据库把对局装回来");
        assertEquals(expectedFen, restored.getBoard().getFen(), "重放出来的盘面必须和存档时一致");
        assertEquals(Side.BLACK, restored.getPlayerSide());
        assertEquals(Side.BLACK, restored.getBoard().getTurn(), "红黑红走了 3 步，轮到黑方");
        assertEquals(3, restored.getMoves().size());
        assertEquals(3, restored.getMessages().size());
        assertEquals(1, restored.getFouls().size());
        assertEquals(original.getMoves().stream().map(ChessMove::getChinese).toList(),
                restored.getMoves().stream().map(ChessMove::getChinese).toList(),
                "棋谱的中文记谱也要一字不差");
    }

    @Test
    @DisplayName("用户摆过棋的对局，读档也从那个残局摆起，而不是标准开局")
    void restoresFromCustomInitialPosition() {
        // 一个简单残局：黑方将士 vs 红方车马帅，轮到黑方走
        String customFen = "3ak4/9/9/9/9/9/4N4/9/4R4/4K4 b - - 0 1";
        Board start = FenParser.parse("g-setup", customFen);
        assertTrue(FenParser.problems(start).isEmpty(), "这个残局本身得合法，测试才有意义");
        assertEquals(Side.BLACK, start.getTurn());

        GameSession original = new GameSession("g-setup", start, customFen);
        original.setPlayerSide(Side.RED);
        play(original, "e9f9");   // 黑 将5平4（e9 = 第 1 行第 5 列）
        play(original, "e1e2");   // 红 车五进一
        String expectedFen = original.getBoard().getFen();

        store().persist(original);

        ChessGame storedRow = captureHeader();
        List<ChessMove> storedMoves = captureMoves();
        assertEquals(2, storedMoves.size());
        assertEquals(customFen, storedRow.getInitialFen(), "开局局面要原样存下来");

        when(gameMapper.selectById("g-setup")).thenReturn(storedRow);
        when(moveMapper.selectList(any())).thenReturn(storedMoves);
        when(foulMapper.selectList(any())).thenReturn(List.of());
        when(chatMapper.selectList(any())).thenReturn(List.of());

        GameSession restored = store().get("g-setup");

        assertNotNull(restored);
        assertEquals(2, restored.getBoard().moveCount(), "两手棋都要重放出来（若误用标准开局，第一步就下不去）");
        assertEquals(expectedFen, restored.getBoard().getFen(), "还原出来的必须是那个残局");
        assertEquals(customFen, restored.getInitialFen());
    }

    @Test
    @DisplayName("内存命中时不查库")
    void memoryHitSkipsDatabase() {
        GameSessionStore store = store();
        GameSession session = store.create("g-mem", Side.RED);

        assertTrue(session == store.get("g-mem"));
        verify(gameMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("库里没有这局时返回 null，而不是抛异常")
    void missingSessionReturnsNull() {
        when(gameMapper.selectById("nope")).thenReturn(null);
        assertNull(store().get("nope"));
    }

    @Test
    @DisplayName("读档时遇到走不下去的着法会就地截断，不丢整局")
    void stopsAtBrokenMove() {
        ChessGame row = new ChessGame();
        row.setId("g-bad");
        row.setPlayerSide("RED");
        row.setStatus("PLAYING");
        row.setTurn("RED");
        row.setMoveCount(2);

        ChessMove good = new ChessMove(1, Side.RED, "h2e2", "炮二平五", false);
        ChessMove bad = new ChessMove(2, Side.RED, "a0a1", "车九进一", false); // 轮到黑方却记成红方

        when(gameMapper.selectById("g-bad")).thenReturn(row);
        when(moveMapper.selectList(any())).thenReturn(List.of(good, bad));
        when(foulMapper.selectList(any())).thenReturn(List.of());
        when(chatMapper.selectList(any())).thenReturn(List.of());

        GameSession restored = new GameSessionStore(repository).get("g-bad");

        assertNotNull(restored);
        assertEquals(1, restored.getBoard().moveCount(), "只走出第一步，第二步被截断");
        assertEquals(Side.BLACK, restored.getBoard().getTurn(), "截断后轮到黑方");
        assertEquals("炮二平五", restored.getMoves().get(0).getChinese());
    }

    // ==================== 辅助 ====================

    private GameSessionStore store() {
        return new GameSessionStore(repository);
    }

    /** 一局标准开局、还没走过棋的会话 */
    private static GameSession newSession(String id) {
        Board board = new Board(id);
        return new GameSession(id, board, board.getFen());
    }

    /** 在会话上走一步并记谱，等价于 GameService 里的走子流程 */
    private static void play(GameSession session, String uci) {
        Board board = session.getBoard();
        Move move = new Move(uci);
        String chinese = ChineseNotation.toChinese(board, move);
        boolean captured = board.at(move.to.r, move.to.c) != null;
        Side mover = board.getTurn();
        assertTrue(board.makeMove(move), "测试自己走了一步非法棋：" + uci);
        session.addMove(new ChessMove(session.nextMoveNo(), mover, move.coordToUci(), chinese, captured));
    }

    private ChessGame captureHeader() {
        ArgumentCaptor<ChessGame> captor = ArgumentCaptor.forClass(ChessGame.class);
        verify(gameMapper).insert(captor.capture());
        return captor.getValue();
    }

    private List<ChessMove> captureMoves() {
        ArgumentCaptor<ChessMove> captor = ArgumentCaptor.forClass(ChessMove.class);
        verify(moveMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    private List<ChessFoul> captureFouls() {
        ArgumentCaptor<ChessFoul> captor = ArgumentCaptor.forClass(ChessFoul.class);
        verify(foulMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }

    private List<ChessChatMessage> captureMessages() {
        ArgumentCaptor<ChessChatMessage> captor = ArgumentCaptor.forClass(ChessChatMessage.class);
        verify(chatMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }
}
