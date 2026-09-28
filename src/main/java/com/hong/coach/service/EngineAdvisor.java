package com.hong.coach.service;

import com.hong.coach.game.Board;
import com.hong.coach.game.Move;
import com.hong.coach.game.Side;
import com.hong.coach.game.XqRules.Piece;
import com.hong.coach.game.XqRules.Pos;
import com.hong.coach.engine.EngineAnalysis;
import com.hong.coach.engine.EngineProperties;
import com.hong.coach.engine.EngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 引擎顾问：把 {@link EngineService} 的原始能力包装成对局层好用的几个动作。
 * <p>
 * 职责边界是「问引擎要结果，并保证结果一定能落到当前盘面上」：
 * <ul>
 *   <li>分析失败不抛异常，返回 null，让上层按「没有引擎数据」继续走；</li>
 *   <li>引擎给出的着法必须先用自研规则复核合法性，非法就退到主变，
 *       再不行才随机兜底 —— 引擎与自研规则的记谱只要有一点点偏差，
 *       都会在这里被挡住，不会走出非法棋。</li>
 * </ul>
 */
@Slf4j
@Service
public class EngineAdvisor {

    private final EngineService engine;
    private final EngineProperties props;

    public EngineAdvisor(EngineService engine, EngineProperties props) {
        this.engine = engine;
        this.props = props;
    }

    public boolean isAvailable() {
        return engine.isAvailable();
    }

    public String statusText() {
        return engine.statusText();
    }

    /**
     * 复盘分析：思考时间长一点，带多路主变，供讲解使用。失败返回 null。
     */
    public EngineAnalysis analyzeForReview(String fen) {
        return safeAnalyze(fen, true);
    }

    /**
     * 对局分析：快一点，只要最好的那一步。失败返回 null。
     */
    public EngineAnalysis analyzeForPlaying(String fen) {
        return safeAnalyze(fen, false);
    }

    private EngineAnalysis safeAnalyze(String fen, boolean review) {
        try {
            return review ? engine.analyzeForReview(fen) : engine.analyzeForPlaying(fen);
        } catch (Exception e) {
            log.warn("引擎分析失败 fen={}：{}", fen, e.getMessage());
            return null;
        }
    }

    /**
     * 从引擎结果里挑一个当前盘面上确实合法的着法。
     * 依次尝试 bestmove → 主变各步 → 全盘随机。全盘都没得走时返回 null。
     */
    public Move chooseMove(Board board, EngineAnalysis analysis) {
        if (analysis != null) {
            Move m = legalOrNull(board, analysis.getBestMove());
            if (m != null) return m;
            for (String uci : analysis.principalVariation()) {
                m = legalOrNull(board, uci);
                if (m != null) return m;
            }
            log.warn("引擎给出的着法在当前盘面上都不合法，改用随机着法。bestmove={} fen={}",
                    analysis.getBestMove(), board.getFen());
        }
        List<Move> all = allLegalMoves(board, board.getTurn());
        if (all.isEmpty()) return null;
        return all.get(ThreadLocalRandom.current().nextInt(all.size()));
    }

    /**
     * 指定方在当前盘面上的全部合法着法
     */
    public List<Move> allLegalMoves(Board board, Side side) {
        List<Move> out = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece p = board.at(r, c);
                if (p != null && p.side == side) {
                    out.addAll(board.legalMovesAt(new Pos(r, c)));
                }
            }
        }
        return out;
    }

    private Move legalOrNull(Board board, String uci) {
        if (uci == null || uci.isBlank() || "none".equalsIgnoreCase(uci.trim())) return null;
        try {
            Move m = new Move(uci);
            return board.isLegal(m) ? m : null;
        } catch (Exception e) {
            return null;
        }
    }
}
