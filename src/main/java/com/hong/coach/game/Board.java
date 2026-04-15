package com.hong.coach.game;

import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * 棋盘
 */
@Data
public final class Board {
    /**
     * 走棋历史
     */
    private LinkedList<Move> moveHistory;
    public Board(String id) {
        this.id = id;
        newGame();
    }

    /**
     * 棋盘ID
     */
    private String id;
    /**
     * 记录现在该轮到哪边走了
     * 默认红方先行
     */
    private Side turn = Side.RED;
    /**
     * 棋局结果
     */
    private GameResultEnum gameState = GameResultEnum.PLAYING;
    /**
     * 胜者
     */
    private Side winner  = null;

    private final XqRules.Piece[][] grid = new XqRules.Piece[10][9];

    public boolean in(int r, int c) {
        return r >= 0 && r < 10 && c >= 0 && c < 9;
    }

    public XqRules.Piece at(int r, int c) {
        return in(r, c) ? grid[r][c] : null;
    }

    public void set(int r, int c, XqRules.Piece p) {
        if (in(r, c)) {
            grid[r][c] = p;
            if (p != null) p.pos = new XqRules.Pos(r, c);
        }
    }

    /**
     * 创建个新棋盘 摆好棋子
     */
    public Board newGame() {
        // 默认红方先走
        turn = Side.RED;
        // 创建黑方原始棋子
        this.set(0, 0, new XqRules.Rook(Side.BLACK, new XqRules.Pos(0, 0)));
        this.set(0, 1, new XqRules.Horse(Side.BLACK, new XqRules.Pos(0, 1)));
        this.set(0, 2, new XqRules.Elephant(Side.BLACK, new XqRules.Pos(0, 2)));
        this.set(0, 3, new XqRules.Advisor(Side.BLACK, new XqRules.Pos(0, 3)));
        this.set(0, 4, new XqRules.General(Side.BLACK, new XqRules.Pos(0, 4)));
        this.set(0, 5, new XqRules.Advisor(Side.BLACK, new XqRules.Pos(0, 5)));
        this.set(0, 6, new XqRules.Elephant(Side.BLACK, new XqRules.Pos(0, 6)));
        this.set(0, 7, new XqRules.Horse(Side.BLACK, new XqRules.Pos(0, 7)));
        this.set(0, 8, new XqRules.Rook(Side.BLACK, new XqRules.Pos(0, 8)));
        this.set(2, 1, new XqRules.Cannon(Side.BLACK, new XqRules.Pos(2, 1)));
        this.set(2, 7, new XqRules.Cannon(Side.BLACK, new XqRules.Pos(2, 7)));
        for (int c = 0; c < 9; c += 2) this.set(3, c, new XqRules.Pawn(Side.BLACK, new XqRules.Pos(3, c)));

        // 红方棋子
        this.set(9, 0, new XqRules.Rook(Side.RED, new XqRules.Pos(9, 0)));
        this.set(9, 1, new XqRules.Horse(Side.RED, new XqRules.Pos(9, 1)));
        this.set(9, 2, new XqRules.Elephant(Side.RED, new XqRules.Pos(9, 2)));
        this.set(9, 3, new XqRules.Advisor(Side.RED, new XqRules.Pos(9, 3)));
        this.set(9, 4, new XqRules.General(Side.RED, new XqRules.Pos(9, 4)));
        this.set(9, 5, new XqRules.Advisor(Side.RED, new XqRules.Pos(9, 5)));
        this.set(9, 6, new XqRules.Elephant(Side.RED, new XqRules.Pos(9, 6)));
        this.set(9, 7, new XqRules.Horse(Side.RED, new XqRules.Pos(9, 7)));
        this.set(9, 8, new XqRules.Rook(Side.RED, new XqRules.Pos(9, 8)));
        this.set(7, 1, new XqRules.Cannon(Side.RED, new XqRules.Pos(7, 1)));
        this.set(7, 7, new XqRules.Cannon(Side.RED, new XqRules.Pos(7, 7)));
        for (int c = 0; c < 9; c += 2) this.set(6, c, new XqRules.Pawn(Side.RED, new XqRules.Pos(6, c)));
        return this;
    }

    /**
     * 九宫格检测
     * 主要是看老将 不能走出九宫格
     */
    public boolean inPalace(Side s, int r, int c) {
        if (!in(r, c)) return false;
        if (s == Side.RED) return r >= 7 && r <= 9 && c >= 3 && c <= 5;
        else return r >= 0 && r <= 2 && c >= 3 && c <= 5;
    }

    /**
     * 找到老将的位置
     */
    public XqRules.Pos findGeneral(Side s) {
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                XqRules.Piece p = grid[r][c];
                if (p != null && p.type == XqRules.PieceType.GENERAL && p.side == s) return new XqRules.Pos(r, c);
            }
        return null;
    }

    /**
     * Check if column is clear between two rows (excluding ends)
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
        XqRules.Pos r = findGeneral(Side.RED), k = findGeneral(Side.BLACK);
        if (r == null || k == null) return false;
        if (r.c != k.c) return false;
        return clearBetweenSameCol(r.r, k.r, r.c);
    }

    /**
     * 移动棋子
     */
    public void makeMove(Move m) {
        XqRules.Piece p = this.at(m.from.r, m.from.c);
        m.setToPiece(this.at(m.to.r, m.to.c));
        this.set(m.from.r, m.from.c, null);
        this.set(m.to.r, m.to.c, p);
        moveHistory.push(m);
        if(this.isCheckMate()){
            gameOver();
        }else{
            // 换手
            this.switchTurn();
        }
    }

    /**
     * 回退一步/悔棋
     */
    public void retriveMove(){
        Move move = moveHistory.pop();
        XqRules.Piece piece = this.at(move.to.r, move.to.c);
        this.set(move.to.r,move.to.c, move.getToPiece());
        this.set(move.from.r,move.from.c, piece);
    }

    /**
     * 棋局分出胜负后的处理
     */
    private void gameOver() {
        this.winner = turn;
        this.gameState = GameResultEnum.END;
    }

    /**
     * 检测是否被将军
     */
    public boolean inCheck(Side side) {
        XqRules.Pos g = findGeneral(side);
        if (g == null) return false;
        // 枚举所有地方棋子的合法移动 检测将军
        for (int r = 0; r < 10; r++)
            for (int c = 0; c < 9; c++) {
                XqRules.Piece q = grid[r][c];
                if (q == null || q.side == side) continue;
                for (XqRules.Pos dst : q.pseudoLegalMoves(this)) {
                    if (dst.r == g.r && dst.c == g.c) return true;
                }
            }
        return generalsFacing();
    }

    /**
     * 找出给定位置的棋子所有合法的走法
     * 1.走完之后不能老将对脸
     * 2.走完之后不能老将被吃
     */
    public List<Move> legalMovesAt(XqRules.Pos from) {
        XqRules.Piece p = at(from.r, from.c);
        if (p == null) return Collections.emptyList();
        List<Move> out = new ArrayList<>();
        for (XqRules.Pos to : p.pseudoLegalMoves(this)) {
            boolean islegal = true;
            makeMove(new Move(from, to));
            if (this.generalsFacing()) islegal = false;
            if (this.inCheck(p.side)) islegal = false;
            if(islegal){
                out.add(new Move(from, to));
            }
            retriveMove();
        }
        return out;
    }


    /**
     * 换手
     */
    public void switchTurn() {
        if (this.turn == Side.RED) {
            this.turn = Side.BLACK;
        } else {
            this.turn = Side.RED;
        }
    }

    /**
     * 检测还有没有棋可以走
     * 没棋走了 要么就是绝杀/要么困毙
     */
    public boolean isCheckMate() {
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                XqRules.Piece piece = this.at(r, c);
                if (piece != null && piece.side == turn) {
                    List<Move> legalMoves = this.legalMovesAt(new XqRules.Pos(r, c));
                    if (!legalMoves.isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 将当前棋盘的盘面转换为fen表示法
     * @return
     */
    public String getFen() {
        return null;
    }
}