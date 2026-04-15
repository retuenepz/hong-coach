package com.hong.coach.game;

import lombok.Data;
import org.springframework.validation.annotation.Validated;

/**
 * 移动
 */
@Data
public  class Move {
    public XqRules.Pos from, to;
    /**
     * 这次移动如果吃掉了子 记录下那颗子
     */
    private XqRules.Piece toPiece;
    public Move (String uci){
        if (uci == null || uci.length() < 4)
            throw new IllegalArgumentException("非法的uci");
        int fromC = uci.charAt(0) - 'a';
        int fromR = 9 - (uci.charAt(1) - '0');
        int toC = uci.charAt(2) - 'a';
        int toR = 9 - (uci.charAt(3) - '0');
        this.from = new XqRules.Pos(fromC, fromR);
        this.to = new XqRules.Pos(toC, toR);
    }
    public Move(XqRules.Pos from, XqRules.Pos to) {
        this.from = from;
        this.to = to;
    }

    public Move(@Validated PositionMove move) {
        this.from = new XqRules.Pos(move.getFromR(), move.getFromC());
        this.to = new XqRules.Pos(move.getToR(), move.getToC());
    }

    @Override
    public String toString() {
        return from + "->" + to;
    }

    /**
     * 获取当前移动的UCI表示法
     * @return
     */
    public String coordToUci() {
        return "" + (char)('a' + this.from.c) + (9 - this.from.r)
                + (char)('a' + this.to.c)   + (9 - this.to.r);
    }
}