package com.hong.coach.game;

import com.hong.coach.game.XqRules.Piece;
import com.hong.coach.game.XqRules.PieceType;
import com.hong.coach.game.XqRules.Pos;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * 棋盘
 * <p>
 * 只负责盘面与规则，不关心引擎与网络。
 * <p>
 * 重要约定：凡是「试探性」改动盘面的地方（生成合法着法、判断将军等），
 * 一律走 {@link #applyMove(Move)} / {@link #undoApply(Move)}。这一对方法
 * 只动棋子，不动回合、胜负、棋谱，因此不会污染棋局状态 —— 这是原实现
 * 用 {@code makeMove()/retriveMove()} 做试探导致状态错乱问题的修复。
 */
@Getter
public final class Board {

    /** 棋盘ID */
    private final String id;

    /** 记录现在该轮到哪边走了，默认红方先行 */
    private Side turn = Side.RED;

    /** 棋局结果 */
    private GameResultEnum gameState = GameResultEnum.PLAYING;

    /** 胜者；和棋为 null */
    private Side winner = null;

    /** 走棋历史，栈顶为最后一步 */
    private final LinkedList<Move> moveHistory = new LinkedList<>();

    /** 最后一步（用于前端高亮） */
    private Move lastMove = null;

    private final Piece[][] grid = new Piece[10][9];

    public Board(String id) {
        this.id = id;
        newGame();
    }

    public boolean in(int r, int c) {
        return r >= 0 && r < 10 && c >= 0 && c < 9;
    }

    public Piece at(int r, int c) {
        return in(r, c) ? grid[r][c] : null;
    }

    public void set(int r, int c, Piece p) {
        if (in(r, c)) {
            grid[r][c] = p;
            if (p != null) p.pos = new Pos(r, c);
        }
    }

    public int moveCount() {
        return moveHistory.size();
    }

    public boolean isGameOver() {
        return gameState == GameResultEnum.END;
    }

    /**
     * 清空成一个空棋盘，回合回到红方、结果清空。
     * <p>
     * 摆棋（{@link #newGame()} 与用户自定义局面）都从这里开始，保证「重开」的语义只有一处实现。
     */
    public Board reset() {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) grid[r][c] = null;
        }
        moveHistory.clear();
        lastMove = null;
        turn = Side.RED;
        gameState = GameResultEnum.PLAYING;
        winner = null;
        return this;
    }

    /**
     * 指定轮到谁走。摆棋时用（局面可能不是红方先行），对局进行中不应调用。
     */
    public void setTurn(Side side) {
        if (side != null) this.turn = side;
    }

    /**
     * 创建个新棋盘 摆好棋子
     */
    public Board newGame() {
        reset();

        // 黑方棋子
        set(0, 0, new XqRules.Rook(Side.BLACK, new Pos(0, 0)));
        set(0, 1, new XqRules.Horse(Side.BLACK, new Pos(0, 1)));
        set(0, 2, new XqRules.Elephant(Side.BLACK, new Pos(0, 2)));
        set(0, 3, new XqRules.Advisor(Side.BLACK, new Pos(0, 3)));
        set(0, 4, new XqRules.General(Side.BLACK, new Pos(0, 4)));
        set(0, 5, new XqRules.Advisor(Side.BLACK, new Pos(0, 5)));
        set(0, 6, new XqRules.Elephant(Side.BLACK, new Pos(0, 6)));
        set(0, 7, new XqRules.Horse(Side.BLACK, new Pos(0, 7)));
        set(0, 8, new XqRules.Rook(Side.BLACK, new Pos(0, 8)));
        set(2, 1, new XqRules.Cannon(Side.BLACK, new Pos(2, 1)));
        set(2, 7, new XqRules.Cannon(Side.BLACK, new Pos(2, 7)));
        for (int c = 0; c < 9; c += 2) set(3, c, new XqRules.Pawn(Side.BLACK, new Pos(3, c)));

        // 红方棋子
        set(9, 0, new XqRules.Rook(Side.RED, new Pos(9, 0)));
        set(9, 1, new XqRules.Horse(Side.RED, new Pos(9, 1)));
        set(9, 2, new XqRules.Elephant(Side.RED, new Pos(9, 2)));
        set(9, 3, new XqRules.Advisor(Side.RED, new Pos(9, 3)));
        set(9, 4, new XqRules.General(Side.RED, new Pos(9, 4)));
        set(9, 5, new XqRules.Advisor(Side.RED, new Pos(9, 5)));
        set(9, 6, new XqRules.Elephant(Side.RED, new Pos(9, 6)));
        set(9, 7, new XqRules.Horse(Side.RED, new Pos(9, 7)));
        set(9, 8, new XqRules.Rook(Side.RED, new Pos(9, 8)));
        set(7, 1, new XqRules.Cannon(Side.RED, new Pos(7, 1)));
        set(7, 7, new XqRules.Cannon(Side.RED, new Pos(7, 7)));
        for (int c = 0; c < 9; c += 2) set(6, c, new XqRules.Pawn(Side.RED, new Pos(6, c)));
        return this;
    }

    /**
     * 九宫格检测 主要是看老将 不能走出九宫格
     */
    public boolean inPalace(Side s, int r, int c) {
        if (!in(r, c)) return false;
        if (s == Side.RED) return r >= 7 && r <= 9 && c >= 3 && c <= 5;
        else return r >= 0 && r <= 2 && c >= 3 && c <= 5;
    }

    /**
     * 找到老将的位置
     */
    public Pos findGeneral(Side s) {
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                Piece p = grid[r][c];
                if (p != null && p.type == PieceType.GENERAL && p.side == s) return new Pos(r, c);
            }
        return null;
    }

    /**
     * 检测同一列两行之间是否无子（不含两端）
     */
    public boolean clearBetweenSameCol(int r1, int r2, int c) {
        int a = Math.min(r1, r2) + 1, b = Math.max(r1, r2) - 1;
        for (int r = a; r <= b; r++) if (grid[r][c] != null) return false;
        return true;
    }

    /**
     * 检测老将是否对脸
     */
    public boolean generalsFacing() {
        Pos r = findGeneral(Side.RED), k = findGeneral(Side.BLACK);
        if (r == null || k == null) return false;
        if (r.c != k.c) return false;
        return clearBetweenSameCol(r.r, k.r, r.c);
    }

    // ==================== 盘面改动的唯一入口 ====================

    /**
     * 纯粹地把棋子从 from 挪到 to，不改变回合、胜负、棋谱。
     * 供规则试探使用。
     */
    private void applyMove(Move m) {
        Piece p = at(m.from.r, m.from.c);
        m.setToPiece(at(m.to.r, m.to.c));
        set(m.from.r, m.from.c, null);
        set(m.to.r, m.to.c, p);
    }

    /**
     * {@link #applyMove(Move)} 的逆操作。
     */
    private void undoApply(Move m) {
        Piece p = at(m.to.r, m.to.c);
        set(m.to.r, m.to.c, m.getToPiece());
        set(m.from.r, m.from.c, p);
    }

    // ==================== 对外走子 ====================

    /**
     * 正式走一步棋：改盘面、记棋谱、判定终局、换手。
     *
     * @return 是否成功走子（棋局已结束或坐标非法时返回 false）
     */
    public boolean makeMove(Move m) {
        if (isGameOver()) return false;
        Piece p = at(m.from.r, m.from.c);
        if (p == null || p.side != turn) return false;

        applyMove(m);
        moveHistory.push(m);
        lastMove = m;

        Side next = turn.opponent();
        if (!hasLegalMove(next)) {
            // 对方无任何合法着法：绝杀或困毙，中国象棋中都判负
            gameState = GameResultEnum.END;
            winner = turn;
        } else if (isBareGenerals()) {
            // 双方只剩将帅，判和
            gameState = GameResultEnum.END;
            winner = null;
        }
        turn = next;
        return true;
    }

    /**
     * 悔棋：回退一步并恢复回合。棋局已结束时也会被重新置为进行中。
     *
     * @return 被撤销的那一步，没有可撤销的着法时返回 null
     */
    public Move undoLastMove() {
        if (moveHistory.isEmpty()) return null;
        Move m = moveHistory.pop();
        undoApply(m);
        turn = turn.opponent();
        gameState = GameResultEnum.PLAYING;
        winner = null;
        lastMove = moveHistory.peek();
        return m;
    }

    // ==================== 规则判定 ====================

    /**
     * 检测是否被将军（含老将对脸）
     */
    public boolean inCheck(Side side) {
        Pos g = findGeneral(side);
        if (g == null) return false;
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                Piece q = grid[r][c];
                if (q == null || q.side == side) continue;
                for (Pos dst : q.pseudoLegalMoves(this)) {
                    if (dst.r == g.r && dst.c == g.c) return true;
                }
            }
        return false;
    }

    /**
     * 找出给定位置的棋子所有合法的走法：
     * 1.走完之后不能老将对脸
     * 2.走完之后不能老将被吃
     */
    public List<Move> legalMovesAt(Pos from) {
        Piece p = at(from.r, from.c);
        if (p == null) return Collections.emptyList();
        List<Move> out = new ArrayList<>();
        for (Pos to : p.pseudoLegalMoves(this)) {
            Move m = new Move(from, to);
            applyMove(m);
            boolean ok = !generalsFacing() && !inCheck(p.side);
            undoApply(m);
            if (ok) out.add(m);
        }
        return out;
    }

    /**
     * 该走法在当前位置上是否合法。
     * <p>
     * 两条门槛：<b>必须是轮到走棋那一方的棋子</b>，且走完之后不能老将对脸、不能被将。
     * 只校验几何合法性是不够的 —— 那样对方可以「替」你走子，棋谱和盘面会直接对不上。
     */
    public boolean isLegal(Move m) {
        if (m == null || m.from == null || m.to == null) return false;
        Piece p = at(m.from.r, m.from.c);
        if (p == null || p.side != turn) return false;
        for (Move legal : legalMovesAt(m.from)) {
            if (legal.to.equals(m.to)) return true;
        }
        return false;
    }

    /**
     * 指定方是否还有棋可以走。没棋走 = 绝杀或困毙。
     */
    public boolean hasLegalMove(Side side) {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = grid[r][c];
                if (piece != null && piece.side == side && !legalMovesAt(new Pos(r, c)).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 双方是否只剩将帅
     */
    private boolean isBareGenerals() {
        return pieceCount() == 2;
    }

    /**
     * 盘面棋子总数
     */
    public int pieceCount() {
        int count = 0;
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++)
                if (grid[r][c] != null) count++;
        return count;
    }

    /**
     * 复制一份独立盘面（棋子对象也会复制），用于试算主变等不影响真实棋局的操作
     */
    public Board copy() {
        Board b = new Board(id + "#copy");
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece p = grid[r][c];
                b.set(r, c, p == null ? null : XqRules.create(p.type, p.side, new Pos(r, c)));
            }
        }
        b.turn = this.turn;
        return b;
    }

    /**
     * 把一串 UCI 着法在副本上顺次走出，返回对应的中文记谱。
     * 遇到不合法的着法即停止，保证不会因为引擎给出的怪招影响真实棋局。
     */
    public List<String> describeSequence(List<String> uciMoves) {
        List<String> out = new ArrayList<>();
        if (uciMoves == null || uciMoves.isEmpty()) return out;
        Board scratch = copy();
        for (String uci : uciMoves) {
            try {
                Move m = new Move(uci);
                if (!scratch.isLegal(m)) break;
                out.add(ChineseNotation.toChinese(scratch, m));
                scratch.makeMove(m);
            } catch (Exception e) {
                break;
            }
        }
        return out;
    }

    // ==================== 序列化 ====================

    /**
     * 当前盘面位置标识，用于重复局面（长将/长捉）判断
     */
    public String positionKey() {
        return getFen().split(" ")[0] + "|" + turn;
    }

    /**
     * 将当前棋盘转换为中国象棋 FEN（Forsyth–Edwards Notation）表示法
     */
    public String getFen() {
        return getFen(turn);
    }

    /**
     * 指定行棋方的 FEN
     */
    public String getFen(Side sideToMove) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 10; r++) {
            int empty = 0;
            for (int c = 0; c < 9; c++) {
                Piece p = grid[r][c];
                if (p == null) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    sb.append(empty);
                    empty = 0;
                }
                char ch = letterOf(p.type);
                sb.append(p.side == Side.RED ? Character.toUpperCase(ch) : Character.toLowerCase(ch));
            }
            if (empty > 0) sb.append(empty);
            if (r < 9) sb.append('/');
        }
        sb.append(' ').append(sideToMove == Side.RED ? 'w' : 'b');
        sb.append(" - - 0 ").append(moveHistory.size() / 2 + 1);
        return sb.toString();
    }

    private static char letterOf(PieceType t) {
        switch (t) {
            case ROOK: return 'r';
            case HORSE: return 'n';
            case ELEPHANT: return 'b';
            case ADVISOR: return 'a';
            case GENERAL: return 'k';
            case CANNON: return 'c';
            case PAWN: return 'p';
            default: return '?';
        }
    }

    /**
     * 输出给前端的盘面矩阵：10 行 × 9 列，空位为 null。
     * 每个元素是单个字符：大写=红方，小写=黑方，字母含义同 FEN。
     */
    public List<List<String>> toMatrix() {
        List<List<String>> rows = new ArrayList<>(10);
        for (int r = 0; r < 10; r++) {
            List<String> row = new ArrayList<>(9);
            for (int c = 0; c < 9; c++) {
                Piece p = grid[r][c];
                if (p == null) row.add(null);
                else {
                    char ch = letterOf(p.type);
                    row.add(String.valueOf(p.side == Side.RED ? Character.toUpperCase(ch) : ch));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 棋谱（UCI 序列），按时间先后排列
     */
    public List<String> moveHistoryUci() {
        List<String> out = new ArrayList<>(moveHistory.size());
        for (int i = moveHistory.size() - 1; i >= 0; i--) {
            out.add(moveHistory.get(i).coordToUci());
        }
        return out;
    }

    /**
     * 输出人类可读的棋盘文本图（黑方在上、红方在下），供大模型理解盘面使用。
     * 第一行是黑方视角的纵线号，最后一行是红方视角的纵线号。
     */
    public String toTextDiagram() {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < 9; c++) sb.append(c == 0 ? "" : " ").append(fileLabelBlack(c));
        sb.append('\n');
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece p = grid[r][c];
                sb.append(c == 0 ? "" : " ").append(p == null ? "·" : displayName(p));
            }
            if (r == 4) sb.append("   黑方 / 红方 分界（河界）");
            sb.append('\n');
        }
        for (int c = 0; c < 9; c++) sb.append(c == 0 ? "" : " ").append(fileLabelRed(c));
        sb.append('\n');
        return sb.toString();
    }

    private static String fileLabelBlack(int col) {
        return String.valueOf(col + 1);
    }

    private static String fileLabelRed(int col) {
        return "一二三四五六七八九".substring(8 - col, 9 - col);
    }

    /**
     * 红方用简体字（帅仕相马车炮兵），黑方用繁体字（将士象馬車砲卒），便于分辨阵营
     */
    private static String displayName(Piece p) {
        boolean red = p.side == Side.RED;
        switch (p.type) {
            case GENERAL: return red ? "帅" : "将";
            case ADVISOR: return red ? "仕" : "士";
            case ELEPHANT: return red ? "相" : "象";
            case HORSE: return red ? "马" : "馬";
            case ROOK: return red ? "车" : "車";
            case CANNON: return red ? "炮" : "砲";
            case PAWN: return red ? "兵" : "卒";
            default: return "?";
        }
    }
}
