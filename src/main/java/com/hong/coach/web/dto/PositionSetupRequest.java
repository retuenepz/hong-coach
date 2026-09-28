package com.hong.coach.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 摆棋请求：棋子矩阵与局面文本（FEN）二选一，给了矩阵就优先用矩阵。
 * <p>
 * 前端把「用户在棋盘上摆的结果」当矩阵交上来，把「粘贴的一段局面」当 FEN 交上来，
 * 服务端统一解析、统一校验，界面自己不需要懂象棋规则。
 */
@Data
public class PositionSetupRequest {

    /** 用户执哪一方：RED / BLACK，默认红方 */
    private String playerSide;

    /** 轮到哪一方走：RED / BLACK；不传就用 FEN 里写的，都没有则红方先行 */
    private String turn;

    /** 10 行 × 9 列的棋子矩阵，元素是 FEN 字母（大写红方、小写黑方），空格用 null */
    private List<List<String>> matrix;

    /** 局面文本，例如 rnbakabnr/9/1c5c1/... w - - 0 1 */
    private String fen;

    /** 落点行号（0 起，第 1 行 = 黑方底线）；只有「落子前判断」用得着 */
    private Integer r;

    /** 落点列号（0 起） */
    private Integer c;

    /** 想放上去的棋子（FEN 字母，大写红小写黑）；为空表示擦除 */
    private String code;
}
