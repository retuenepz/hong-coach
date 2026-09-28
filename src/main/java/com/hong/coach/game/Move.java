package com.hong.coach.game;

import lombok.Data;

/**
 * 移动
 * <p>
 * 坐标约定：{@link XqRules.Pos} 的第一个参数是行 r（0 在上方黑方底线，9 在下方红方底线），
 * 第二个参数是列 c（0~8，屏幕从左到右）。
 * UCI 记法则是「列字母 + 行数字」，例如 h2e2 表示第 h 列（c=7）第 2 行（r=7）。
 */
@Data
public class Move {
    public XqRules.Pos from, to;
    /**
     * 这次移动如果吃掉了子 记录下那颗子
     */
    private XqRules.Piece toPiece;

    public Move(String uci) {
        if (uci == null || uci.trim().length() < 4)
            throw new IllegalArgumentException("非法的uci: " + uci);
        String s = uci.trim();
        int fromC = s.charAt(0) - 'a';
        int fromR = 9 - (s.charAt(1) - '0');
        int toC = s.charAt(2) - 'a';
        int toR = 9 - (s.charAt(3) - '0');
        this.from = new XqRules.Pos(fromR, fromC);
        this.to = new XqRules.Pos(toR, toC);
    }

    public Move(XqRules.Pos from, XqRules.Pos to) {
        this.from = from;
        this.to = to;
    }

    public Move(PositionMove move) {
        this.from = new XqRules.Pos(move.getFromR(), move.getFromC());
        this.to = new XqRules.Pos(move.getToR(), move.getToC());
    }

    /**
     * 坐标对转 UCI 字符串
     */
    public static String toUci(XqRules.Pos from, XqRules.Pos to) {
        return "" + (char) ('a' + from.c) + (9 - from.r)
                + (char) ('a' + to.c) + (9 - to.r);
    }

    @Override
    public String toString() {
        return from + "->" + to;
    }

    /**
     * 获取当前移动的UCI表示法
     */
    public String coordToUci() {
        return toUci(this.from, this.to);
    }
}
