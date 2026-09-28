package com.hong.coach.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对局主表：一局的「当前快照」。
 * <p>
 * 只存头部信息与用于自检的 FEN，真正的盘面由 {@link ChessMove} 序列重放得出，
 * 因此这张表不会因为规则调整而存下过期盘面。
 */
@Data
@TableName("chess_game")
public class ChessGame {

    /** 对局标识，由前端生成 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 用户执子方 */
    private String playerSide;

    /** PLAYING / END */
    private String status;

    /** RED / BLACK，和棋为 null */
    private String winner;

    /** 开局时的局面 FEN。标准开局是一副满盘，用户自己摆棋时是那个残局 */
    private String initialFen;

    /** 当前局面 FEN，读档后用于核对重放结果 */
    private String fen;

    /** 当前轮到哪一方走 */
    private String turn;

    /** 已完成着法数 */
    private Integer moveCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
