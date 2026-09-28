package com.hong.coach.coach;

import com.hong.coach.game.Side;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次「讲棋」所需的全部上下文：既有盘面信息，也有引擎给出的评估与主变。
 * 这些数据会经过 CoachService 转写成自然语言提示词，但绝不直接暴露给用户。
 */
@Data
public class TeachContext {

    /** 用户执子方 */
    private Side playerSide = Side.RED;

    /** 当前盘面 FEN（仅内部使用） */
    private String fen;

    /** 全程着法（中文记谱），按先后顺序 */
    private List<String> moveList = new ArrayList<>();

    /** 对局阶段：开局 / 中局 / 残局 */
    private String phase = "开局";

    /** 用户刚走的一步（中文记谱） */
    private String playedMove;

    /** 用户刚走的一步（UCI） */
    private String playedMoveUci;

    /** 用户这步棋之前、用户视角的厘兵分 */
    private int evalBefore;

    /** 用户这步棋之后、用户视角的厘兵分 */
    private int evalAfter;

    /** 评分损失（厘兵），正数表示变差 */
    private int evalLoss;

    /** 这步棋的成色描述：漏着 / 失误 / 稍欠精确 */
    private String quality;

    /** 引擎推荐的更好着法（中文记谱） */
    private String betterMove;

    /** 引擎推荐的更好着法（UCI） */
    private String betterMoveUci;

    /** 引擎主变（中文记谱，含引擎推荐着法本身） */
    private List<String> pvChinese = new ArrayList<>();

    /** 引擎对局面的评分文案 */
    private String scoreText;

    /** 轮到谁走 */
    private Side turn;

    /** 是否已经分出胜负 */
    private boolean gameOver;

    /** 胜负描述 */
    private String resultText;

    /** 是否被将军 */
    private boolean playerInCheck;

    /** 棋盘文本图（黑方在上、红方在下），让大模型更容易理解盘面 */
    private String boardDiagram = "";

    /** 当前局面、用户视角的厘兵分 */
    private int currentEvalCp;

    /** 现在轮到用户走时，引擎建议的一步（中文记谱） */
    private String suggestedMove;

    /** 现在轮到用户走时，引擎建议的一步（UCI） */
    private String suggestedMoveUci;

    /** 现在轮到用户走时的主变（中文记谱） */
    private List<String> suggestedPvChinese = new ArrayList<>();

    public String playerSideText() {
        return playerSide == Side.RED ? "红方" : "黑方";
    }

    public String opponentSideText() {
        return playerSide == Side.RED ? "黑方" : "红方";
    }

    /**
     * 把厘兵分转成「领先/落后多少兵」的口语描述，站在用户视角
     */
    public String evalText(int cp) {
        double pawns = Math.abs(cp) / 100.0;
        if (cp > 30) return String.format("你领先约 %.2f 个兵", pawns);
        if (cp < -30) return String.format("你落后约 %.2f 个兵", pawns);
        return "双方大致均势";
    }
}
