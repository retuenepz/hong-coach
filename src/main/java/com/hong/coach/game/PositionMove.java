package com.hong.coach.game;

import lombok.Data;

/**
 * 前端提交的走子请求，用棋盘行列坐标表达。
 * <p>
 * 坐标约定与 {@link XqRules.Pos} 一致：r 是行（0 在最上方黑方底线，9 在最下方红方底线），
 * c 是列（0~8，屏幕从左到右）。合法性由服务端校验，这里不做参数校验。
 */
@Data
public class PositionMove {
    private Integer fromR;
    private Integer fromC;
    private Integer toR;
    private Integer toC;
}
