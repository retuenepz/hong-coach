package com.hong.coach.repository.dto;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 棋盘
 */
@Data
@TableName("chess_board")
public class ChessBoardDTO {
    private Long id;
    private Date createTime;
}
