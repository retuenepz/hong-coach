package com.hong.coach.engine;

import com.hong.coach.game.Side;
import lombok.Data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 引擎对某个局面的分析结果。
 * <p>
 * 所有评分都是「行棋方视角」：正数表示轮到走棋的一方占优。
 */
@Data
public class EngineAnalysis {

    /** 被分析的局面的 FEN */
    private String fen;

    /** 引擎推荐着法（UCI） */
    private String bestMove;

    /** 引擎预测对方应招（UCI） */
    private String ponder;

    /** 搜索深度 */
    private int depth;

    /** 行棋方视角的厘兵分（一兵=100），杀棋局面下为 null */
    private Integer scoreCp;

    /** 行棋方视角的杀棋步数，正数为己方杀，负数为被对方杀 */
    private Integer scoreMate;

    /** 多主变，第一路为引擎首选 */
    private List<PvLine> pvLines = new ArrayList<>();

    /** 本次分析耗时（毫秒） */
    private long costMs;

    /**
     * 归一化成单个可比较的厘兵分：杀棋折算成很大的分值，便于计算「评分落差」。
     */
    public int evalCp() {
        if (scoreMate != null) {
            return scoreMate > 0 ? 10000 - 10 * scoreMate : -(10000 - 10 * (-scoreMate));
        }
        return scoreCp == null ? 0 : scoreCp;
    }

    public boolean isMate() {
        return scoreMate != null;
    }

    /**
     * 换算成指定方视角的厘兵分，便于跨回合比较。
     * <p>
     * {@link #evalCp()} 是「行棋方视角」，因此必须先知道这一步分析时轮到谁走。
     * 这里直接从 FEN 的第二个字段推断，避免调用方传错视角导致正负号反转。
     */
    public int evalCpFor(Side side) {
        boolean redToMove = true;
        if (fen != null) {
            String[] parts = fen.trim().split("\\s+");
            if (parts.length >= 2) {
                redToMove = "w".equalsIgnoreCase(parts[1]);
            }
        }
        boolean viewerIsRed = (side == Side.RED);
        return redToMove == viewerIsRed ? evalCp() : -evalCp();
    }

    /**
     * 首选主变（UCI 序列），可能为空
     */
    public List<String> principalVariation() {
        if (pvLines.isEmpty() || pvLines.get(0).getMoves() == null) return Collections.emptyList();
        return pvLines.get(0).getMoves();
    }

    /**
     * 人类可读的评分文案
     */
    public String scoreText() {
        if (scoreMate != null) {
            return scoreMate > 0
                    ? "行棋方 " + scoreMate + " 步内可取胜"
                    : "行棋方 " + (-scoreMate) + " 步内被绝杀";
        }
        int cp = evalCp();
        String lead = cp > 0 ? "行棋方占优" : (cp < 0 ? "行棋方落后" : "均势");
        return String.format("%s %+.2f 兵", lead, cp / 100.0);
    }

    @Data
    public static class PvLine {
        private int multipv = 1;
        private Integer scoreCp;
        private Integer scoreMate;
        private Integer depth;
        private List<String> moves = new ArrayList<>();
    }
}
