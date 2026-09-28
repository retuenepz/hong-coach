package com.hong.coach.game;

import com.hong.coach.game.XqRules.Piece;
import com.hong.coach.game.XqRules.PieceType;
import com.hong.coach.game.XqRules.Pos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 中文记谱法（象棋着法记录）。
 * <p>
 * 规则约定：
 * <ul>
 *   <li>红方纵线自右向左为一至九（用中文数字），黑方纵线自左向右为 1 至 9（用阿拉伯数字）。</li>
 *   <li>同一行移动记「平」，否则按红方行号减小、黑方行号增大记「进」，反之记「退」。</li>
 *   <li>车、炮、兵、将进退时记步数；马、相、士斜行时记目标纵线。</li>
 *   <li>同一纵线上有同兵种棋子时，用「前/后」（三个用「前/中/后」）区分。</li>
 * </ul>
 * 任何异常都退化输出 UCI，保证不会因为记谱出错影响对局流程。
 */
public final class ChineseNotation {

    private static final String[] CN_NUM = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};

    private ChineseNotation() {
    }

    /**
     * 把一步棋转成中文记谱。
     *
     * @param board 走这一步「之前」的盘面，用于判断兵种与前后关系
     */
    public static String toChinese(Board board, Move move) {
        if (board == null || move == null || move.from == null || move.to == null) {
            return move == null ? "" : Move.toUci(move.from, move.to);
        }
        try {
            return build(board, move);
        } catch (Exception e) {
            return Move.toUci(move.from, move.to);
        }
    }

    /**
     * 把一步棋转成中文记谱，盘面信息缺失时退化为 UCI
     */
    public static String toChineseOrUci(Board board, String uci) {
        if (uci == null || uci.isBlank()) return "";
        if (board == null) return uci;
        try {
            return toChinese(board, new Move(uci));
        } catch (Exception e) {
            return uci;
        }
    }

    private static String build(Board board, Move m) {
        Piece p = board.at(m.from.r, m.from.c);
        if (p == null) return Move.toUci(m.from, m.to);

        Side side = p.side;
        String name = nameOf(p.type, side);
        String prefix = disambiguation(board, m.from, p);

        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(name);
        if (prefix.isEmpty()) {
            sb.append(label(side, fileOf(side, m.from.c)));
        }

        if (m.from.r == m.to.r) {
            sb.append('平').append(label(side, fileOf(side, m.to.c)));
        } else {
            boolean advance = side == Side.RED ? m.to.r < m.from.r : m.to.r > m.from.r;
            sb.append(advance ? '进' : '退');
            if (isDiagonalMover(p.type)) {
                sb.append(label(side, fileOf(side, m.to.c)));
            } else {
                sb.append(label(side, Math.abs(m.to.r - m.from.r)));
            }
        }
        return sb.toString();
    }

    /**
     * 纵线编号：红方自右向左一至九，黑方自左向右 1 至 9
     */
    private static int fileOf(Side side, int col) {
        return side == Side.RED ? 9 - col : col + 1;
    }

    private static String label(Side side, int number) {
        if (number < 1 || number > 9) return String.valueOf(number);
        return side == Side.RED ? CN_NUM[number - 1] : String.valueOf(number);
    }

    /**
     * 斜行棋子（马、相/象、仕/士）进退时记目标纵线，其余记步数
     */
    private static boolean isDiagonalMover(PieceType type) {
        return type == PieceType.HORSE || type == PieceType.ELEPHANT || type == PieceType.ADVISOR;
    }

    /**
     * 同一纵线上同兵种需要「前/后」区分。返回空串表示无需区分。
     */
    private static String disambiguation(Board board, Pos from, Piece self) {
        List<Integer> rows = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            Piece q = board.at(r, from.c);
            if (q != null && q.type == self.type && q.side == self.side) rows.add(r);
        }
        if (rows.size() < 2 || rows.size() > 3) return "";
        Collections.sort(rows);
        // 「前」指更靠近对方底线的那一个
        if (self.side == Side.BLACK) Collections.reverse(rows);
        int idx = rows.indexOf(from.r);
        if (idx < 0) return "";
        if (rows.size() == 2) return idx == 0 ? "前" : "后";
        return idx == 0 ? "前" : (idx == 1 ? "中" : "后");
    }

    private static String nameOf(PieceType type, Side side) {
        boolean red = side == Side.RED;
        switch (type) {
            case GENERAL: return red ? "帅" : "将";
            case ADVISOR: return red ? "仕" : "士";
            case ELEPHANT: return red ? "相" : "象";
            case HORSE: return "马";
            case ROOK: return "车";
            case CANNON: return "炮";
            case PAWN: return red ? "兵" : "卒";
            default: return "?";
        }
    }
}
