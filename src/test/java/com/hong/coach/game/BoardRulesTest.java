package com.hong.coach.game;

import com.hong.coach.game.XqRules.PieceType;
import com.hong.coach.game.XqRules.Pos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 棋盘规则、FEN 生成、中文记谱的回归测试。
 */
class BoardRulesTest {

    private static final String START_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1";

    @Test
    @DisplayName("起始局面 FEN 正确")
    void startPositionFen() {
        Board board = new Board("t");
        assertEquals(START_FEN, board.getFen());
    }

    @Test
    @DisplayName("UCI 坐标解析：行是行、列是列")
    void uciParsing() {
        Move m = new Move("b7e7");
        // b7 -> 第 2 行(b=列1, 行 9-7=2)、第 1 列
        assertEquals(new Pos(2, 1), m.from);
        // e7 -> 第 2 行、第 4 列
        assertEquals(new Pos(2, 4), m.to);
        assertEquals("b7e7", m.coordToUci());

        Move h2e2 = new Move("h2e2");
        assertEquals(new Pos(7, 7), h2e2.from);
        assertEquals(new Pos(7, 4), h2e2.to);
    }

    @Test
    @DisplayName("起始局面的着法合法性：红方先行，几何不合法的一律拒绝")
    void startMovesAreLegal() {
        Board board = new Board("t");
        assertTrue(board.isLegal(new Move("h2e2")), "红方炮二平五应当合法");
        assertTrue(board.isLegal(new Move("b0c2")), "红方马八进七应当合法");
        assertFalse(board.isLegal(new Move("h2e5")), "炮不能跳过两子后无子可吃");
        assertFalse(board.isLegal(new Move("a0a5")), "车不能穿过自己的兵");
    }

    @Test
    @DisplayName("不能替对方走子：轮到红方时，黑方的着法必须被拒绝")
    void cannotMoveOpponentPieces() {
        Board board = new Board("t");
        // 开局轮红方走，黑方的炮2平5、马8进7 都不该被接受
        assertFalse(board.isLegal(new Move("b7e7")), "红方回合不该能走黑炮");
        assertFalse(board.isLegal(new Move("h9g7")), "红方回合不该能走黑马");
        assertFalse(board.makeMove(new Move("b7e7")), "走对方的子必须落子失败");

        // 走完红方一步，轮到黑方，反过来同理
        assertTrue(board.makeMove(new Move("h2e2")));
        assertEquals(Side.BLACK, board.getTurn());
        assertFalse(board.isLegal(new Move("b0c2")), "黑方回合不该能走红马");
        assertTrue(board.isLegal(new Move("h9g7")), "黑方回合应当能走自己的马");
    }

    @Test
    @DisplayName("中文记谱：红黑双方的纵线编号互不相同")
    void chineseNotation() {
        Board board = new Board("t");
        assertEquals("炮二平五", ChineseNotation.toChinese(board, new Move("h2e2")));
        assertEquals("炮八平五", ChineseNotation.toChinese(board, new Move("b2e2")));
        assertEquals("马八进七", ChineseNotation.toChinese(board, new Move("b0c2")));
        assertEquals("马二进三", ChineseNotation.toChinese(board, new Move("h0g2")));
        assertEquals("兵七进一", ChineseNotation.toChinese(board, new Move("c3c4")));
        assertEquals("炮2平5", ChineseNotation.toChinese(board, new Move("b7e7")));
        assertEquals("马8进7", ChineseNotation.toChinese(board, new Move("h9g7")));
        assertEquals("卒7进1", ChineseNotation.toChinese(board, new Move("g6g5")));
    }

    @Test
    @DisplayName("中文记谱：同一纵线同兵种用前后区分")
    void chineseNotationFrontBack() {
        Board board = empty();
        board.set(0, 4, XqRules.create(PieceType.GENERAL, Side.BLACK, new Pos(0, 4)));
        board.set(9, 3, XqRules.create(PieceType.GENERAL, Side.RED, new Pos(9, 3)));
        board.set(2, 0, XqRules.create(PieceType.ROOK, Side.RED, new Pos(2, 0)));
        board.set(5, 0, XqRules.create(PieceType.ROOK, Side.RED, new Pos(5, 0)));

        // 行号小的更靠前（红方向上行进）
        assertEquals("前车进一", ChineseNotation.toChinese(board, new Move(new Pos(2, 0), new Pos(1, 0))));
        assertEquals("后车进一", ChineseNotation.toChinese(board, new Move(new Pos(5, 0), new Pos(4, 0))));
    }

    @Test
    @DisplayName("走子与悔棋能完整还原盘面")
    void makeAndUndo() {
        Board board = new Board("t");
        String before = board.getFen();

        assertTrue(board.makeMove(new Move("h2e2")));
        assertEquals(Side.BLACK, board.getTurn());
        assertEquals(1, board.moveCount());
        assertFalse(before.equals(board.getFen()));

        assertNotNull(board.undoLastMove());
        assertEquals(Side.RED, board.getTurn());
        assertEquals(0, board.moveCount());
        assertEquals(before, board.getFen());
    }

    @Test
    @DisplayName("走子会记录吃子信息，悔棋后还原被吃的子")
    void captureAndUndo() {
        Board board = empty();
        board.set(9, 3, XqRules.create(PieceType.GENERAL, Side.RED, new Pos(9, 3)));
        board.set(0, 4, XqRules.create(PieceType.GENERAL, Side.BLACK, new Pos(0, 4)));
        board.set(4, 0, XqRules.create(PieceType.ROOK, Side.RED, new Pos(4, 0)));
        board.set(4, 8, XqRules.create(PieceType.PAWN, Side.BLACK, new Pos(4, 8)));

        assertTrue(board.makeMove(new Move(new Pos(4, 0), new Pos(4, 8))));
        assertEquals(PieceType.ROOK, board.at(4, 8).type);
        assertEquals(Side.RED, board.at(4, 8).side);

        board.undoLastMove();
        assertEquals(PieceType.PAWN, board.at(4, 8).type);
        assertEquals(Side.BLACK, board.at(4, 8).side);
        assertEquals(PieceType.ROOK, board.at(4, 0).type);
    }

    @Test
    @DisplayName("绝杀判定：老将无路可走")
    void checkmateDetection() {
        Board board = empty();
        board.set(0, 4, XqRules.create(PieceType.GENERAL, Side.BLACK, new Pos(0, 4)));
        board.set(9, 3, XqRules.create(PieceType.GENERAL, Side.RED, new Pos(9, 3)));
        // 两个车封住黑将所在的两行，第三个车封住下移的一格
        board.set(0, 0, XqRules.create(PieceType.ROOK, Side.RED, new Pos(0, 0)));
        board.set(0, 8, XqRules.create(PieceType.ROOK, Side.RED, new Pos(0, 8)));
        board.set(1, 0, XqRules.create(PieceType.ROOK, Side.RED, new Pos(1, 0)));

        assertTrue(board.inCheck(Side.BLACK), "黑将应当被将军");
        assertFalse(board.hasLegalMove(Side.BLACK), "黑将应当无路可走");
        assertTrue(board.hasLegalMove(Side.RED));
    }

    @Test
    @DisplayName("探查式生成着法不会污染棋局状态")
    void legalMoveProbeDoesNotPolluteState() {
        Board board = new Board("t");
        String fenBefore = board.getFen();
        int historyBefore = board.moveCount();

        List<Move> moves = board.legalMovesAt(new Pos(7, 7));
        assertFalse(moves.isEmpty(), "红方右炮至少能平到中路");

        assertEquals(fenBefore, board.getFen());
        assertEquals(historyBefore, board.moveCount());
        assertEquals(GameResultEnum.PLAYING, board.getGameState());
        assertNotNull(board.findGeneral(Side.RED));
        assertNotNull(board.findGeneral(Side.BLACK));
    }

    @Test
    @DisplayName("老将对脸被识别为非法着法")
    void generalsFacingIsIllegal() {
        Board board = empty();
        board.set(9, 4, XqRules.create(PieceType.GENERAL, Side.RED, new Pos(9, 4)));
        board.set(0, 4, XqRules.create(PieceType.GENERAL, Side.BLACK, new Pos(0, 4)));
        // 中间放一个红车挡着，此时并不对脸
        board.set(5, 4, XqRules.create(PieceType.ROOK, Side.RED, new Pos(5, 4)));
        assertFalse(board.generalsFacing());

        // 车离开这一纵线就会形成对脸，因此这一步不合法
        assertFalse(board.isLegal(new Move(new Pos(5, 4), new Pos(5, 5))));
        // 车仍然挡在同一纵线上则合法
        assertTrue(board.isLegal(new Move(new Pos(5, 4), new Pos(4, 4))));
    }

    @Test
    @DisplayName("主变转中文记谱在副本上试算，不影响真实盘面")
    void describeSequenceOnCopy() {
        Board board = new Board("t");
        String fen = board.getFen();
        List<String> notation = board.describeSequence(List.of("b2e2", "h9g7", "b0c2"));
        assertEquals(List.of("炮八平五", "马8进7", "马八进七"), notation);
        assertEquals(fen, board.getFen());
        assertEquals(0, board.moveCount());
    }

    @Test
    @DisplayName("非法主变会安全截断")
    void describeSequenceStopsOnIllegal() {
        Board board = new Board("t");
        List<String> notation = board.describeSequence(List.of("h2e2", "zzzz", "b0c2"));
        assertEquals(1, notation.size());
    }

    private static Board empty() {
        Board board = new Board("test");
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) board.set(r, c, null);
        }
        return board;
    }
}
