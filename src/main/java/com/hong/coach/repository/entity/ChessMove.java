package com.hong.coach.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.hong.coach.game.Side;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 着法明细。
 * <p>
 * 这张表同时承担两个角色，因此字段上标了 {@link JsonIgnore} 区分「只在库里用」的部分：
 * <ul>
 *   <li>持久化实体：读档时按 moveNo 顺序重放，即可还原任意时刻的盘面；</li>
 *   <li>前端棋谱 / 大模型上下文的记录：只暴露 moveNo、side、chinese、captured、ai。UCI 也一样对外，
 *       但前端不使用，仅供排查问题。</li>
 * </ul>
 */
@Data
@TableName("chess_move")
public class ChessMove {

    @TableId(type = IdType.AUTO)
    @JsonIgnore
    private Long id;

    @JsonIgnore
    private String gameId;

    /** 第几手，从 1 开始 */
    private Integer moveNo;

    /** 走子方 */
    private Side side;

    /** UCI 着法，如 h2e2 */
    private String uci;

    /** 中文记谱，如 炮二平五 */
    private String chinese;

    /** 这一步是否吃子 */
    private boolean captured;

    /** 是否由电脑走出 */
    private boolean ai;

    @JsonIgnore
    private LocalDateTime createdAt;

    public ChessMove() {
    }

    public ChessMove(int moveNo, Side side, String uci, String chinese, boolean captured) {
        this.moveNo = moveNo;
        this.side = side;
        this.uci = uci;
        this.chinese = chinese;
        this.captured = captured;
    }
}
