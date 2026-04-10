package com.hong.coach.game;

import lombok.Data;

import javax.validation.constraints.NotNull;

/**
 * 用横纵坐标的方式 表达棋子的移动
 * 前端所使用的
 */
@Data
public class PositionMove {
    /**
     * 列  row
     */
    @NotNull(message = "移动坐标 fromR 不能为空")
    private Integer fromR;
    @NotNull(message = "移动坐标toR 不能为空")
    private Integer toR;
    /**
     * 行 column
     */
    @NotNull(message = "移动坐标fromC不能为空")
    private Integer fromC;
    @NotNull(message = "移动坐标toC不能为空")
    private Integer toC;
}
