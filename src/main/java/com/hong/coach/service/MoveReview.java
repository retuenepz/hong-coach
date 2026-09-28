package com.hong.coach.service;

import com.hong.coach.game.Side;

import java.util.List;

/**
 * 对「刚走的这一步」的复盘结论，是教练主动讲解的全部素材。
 * <p>
 * 用不可变记录而不是一堆散落字段，是为了保证「走一步棋 → 产生一份复盘」
 * 这件事只有一个来源，不会出现某些字段更新了、另一些忘了更新的情况。
 * <p>
 * 红黑双方都可以由人来手动走，所以复盘必须记住 {@code side}：评分落差是站在
 * <b>走这一步的那一方</b> 算的，教练讲解时也按这一方称呼「你」，否则就会出现
 * 「黑方走了一步棋，教练却按红方视角说他亏了」这种张冠李戴。
 *
 * @param side          走这一步的是哪一方
 * @param playedChinese 走的这一步（中文记谱）
 * @param playedUci     走的这一步（UCI）
 * @param evalBefore    走之前、走子方视角的厘兵分
 * @param evalAfter     走之后、走子方视角的厘兵分
 * @param evalLoss      评分落差，正数表示这一步让走子方亏了
 * @param quality       成色：漏着 / 失误 / 稍欠精确；null 表示不值得一提
 * @param betterChinese 引擎认为更好的着法（中文记谱），可能为 null
 * @param betterUci     引擎认为更好的着法（UCI），可能为 null
 * @param pvChinese     引擎主变（中文记谱），可能为空
 */
public record MoveReview(
        Side side,
        String playedChinese,
        String playedUci,
        int evalBefore,
        int evalAfter,
        int evalLoss,
        String quality,
        String betterChinese,
        String betterUci,
        List<String> pvChinese
) {
    public MoveReview {
        pvChinese = pvChinese == null ? List.of() : List.copyOf(pvChinese);
    }

    /**
     * 评分落差 → 成色描述。小于 40 厘兵（0.4 个兵）的波动属于正常出入，不提示。
     */
    public static String qualityOf(int evalLoss) {
        if (evalLoss >= 400) return "严重漏着";
        if (evalLoss >= 200) return "漏着";
        if (evalLoss >= 80) return "失误";
        if (evalLoss >= 40) return "稍欠精确";
        return null;
    }
}
