package com.hong.coach.service;

import com.hong.coach.game.Board;
import com.hong.coach.game.Side;
import com.hong.coach.engine.EngineAnalysis;
import com.hong.coach.repository.entity.ChessChatMessage;
import com.hong.coach.repository.entity.ChessFoul;
import com.hong.coach.repository.entity.ChessMove;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一局对战的内存状态：棋盘 + 视角方 + 全部记录 + 最近一次复盘素材。
 * <p>
 * 这个类只负责「装状态」和「回答关于自己的简单问题」，不碰引擎、不碰大模型、
 * 不碰数据库 —— 那些都由 {@link GameService} 与 {@link GameSessionStore} 负责。
 * 生命周期：内存里按 TTL 回收，回收后可由 {@link GameSessionStore} 从数据库重建。
 */
@Getter
public class GameSession {

    /** 单局最多保留多少条对话，超出后丢弃最早的（同时限制了落库体积） */
    private static final int MAX_CHAT_MESSAGES = 200;

    private final String id;

    private final Board board;

    /**
     * 这一局是从哪个局面开始的。
     * <p>
     * 标准开局、用户自己摆的残局都记在这里。落库后读档时先用它摆出棋盘、
     * 再按 order 重放着法 —— 摆过棋的对局才不会在读档时被还原成标准开局。
     */
    private final String initialFen;

    /**
     * 用户的<b>视角方</b>：你站在哪一边看这盘棋。
     * <p>
     * 只决定两件事：棋盘往哪边摆（谁在下方），以及教练称呼「你」时指的是谁。
     * 它<b>不</b>限制用户能走哪些棋子 —— 红黑两边都能手动走，
     * 所以每次走子记的是「当时轮到的那一方」，而不是这个字段。
     */
    private Side playerSide = Side.RED;

    /** 着法记录，按时间先后 */
    private final List<ChessMove> moves = new ArrayList<>();

    /** 违规走子记录 */
    private final List<ChessFoul> fouls = new ArrayList<>();

    /** 与教练的对话记录 */
    private final List<ChessChatMessage> messages = new ArrayList<>();

    /** 最近一次「人手动走子」的复盘结论；null 表示没有可讲解的内容 */
    private MoveReview lastReview;

    // ---------- 当前局面分析缓存：同一个局面不重复问引擎 ----------

    private String cachedFen;
    private EngineAnalysis cachedAnalysis;

    private final long createdAt = System.currentTimeMillis();
    private long updatedAt = System.currentTimeMillis();

    public GameSession(String id, Board board, String initialFen) {
        this.id = id;
        this.board = board;
        this.initialFen = initialFen;
    }

    // ==================== 记录 ====================

    /** 下一手的序号，从 1 开始 */
    public int nextMoveNo() {
        return moves.size() + 1;
    }

    public void addMove(ChessMove move) {
        moves.add(move);
    }

    public void addFoul(String detail) {
        fouls.add(new ChessFoul(nextMoveNo(), detail));
    }

    public void addMessage(ChessChatMessage message) {
        messages.add(message);
        if (messages.size() > MAX_CHAT_MESSAGES) {
            messages.subList(0, messages.size() - MAX_CHAT_MESSAGES).clear();
        }
    }

    // ==================== 缓存 ====================

    /**
     * 缓存当前局面的引擎分析，供用户提问时复用
     */
    public EngineAnalysis cachedAnalysisFor(String fen) {
        return fen.equals(cachedFen) ? cachedAnalysis : null;
    }

    public void cacheAnalysis(String fen, EngineAnalysis analysis) {
        this.cachedFen = fen;
        this.cachedAnalysis = analysis;
    }

    /** 盘面变了，缓存作废 */
    public void invalidateCache() {
        this.cachedFen = null;
        this.cachedAnalysis = null;
    }

    // ==================== 状态查询 ====================

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    public void setPlayerSide(Side playerSide) {
        if (playerSide != null) this.playerSide = playerSide;
    }

    public void setLastReview(MoveReview lastReview) {
        this.lastReview = lastReview;
    }

    /**
     * 结算文案，站在用户视角
     */
    public String resultText() {
        if (!board.isGameOver()) return null;
        if (board.getWinner() == null) return "和棋";
        return board.getWinner() == playerSide ? "你赢了" : "你输了";
    }

    /**
     * 更具体的结算描述，供教练与前端使用
     */
    public String resultDetail() {
        if (!board.isGameOver()) return null;
        if (board.getWinner() == null) return "双方均无取胜手段，判和棋。";
        return board.getWinner() == playerSide
                ? "恭喜，你把对手将死了！"
                : "这盘被对手将死了，复盘看看哪一步出了问题。";
    }
}
