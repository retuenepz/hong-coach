package com.hong.coach.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 违规走子记录。用户点了不合法的着法时留痕，前端在「违规记录」里展示。
 */
@Data
@TableName("chess_foul")
public class ChessFoul {

    @TableId(type = IdType.AUTO)
    @JsonIgnore
    private Long id;

    @JsonIgnore
    private String gameId;

    /** 违规发生在第几手之前（1 表示还没走第一步就点了非法着法） */
    private Integer moveNo;

    /** 违规描述，如「第 3 手：ROOK (9,0) → (4,0)」 */
    private String detail;

    @JsonIgnore
    private LocalDateTime createdAt;

    public ChessFoul() {
    }

    public ChessFoul(int moveNo, String detail) {
        this.moveNo = moveNo;
        this.detail = detail;
    }
}
