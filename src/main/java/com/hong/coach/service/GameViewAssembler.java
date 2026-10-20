package com.hong.coach.service;

import com.hong.coach.coach.CoachService;
import com.hong.coach.game.Board;
import com.hong.coach.game.Move;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对局接口的出参组装。
 * <p>
 * 单独拎出来是因为「盘面怎么描述给前端」这件事散落在各个接口里最容易走样 ——
 * 集中在一处，保证所有响应里 {@code board / turn / moves / chat} 的字段含义完全一致，
 * 前端只需要认这一套结构。
 * <p>
 * 约定：<b>响应里只要带了 {@code chat} 字段，前端就整体重绘对话窗口</b>，
 * 因此每个会改动对话的接口都必须带上它（不变量比省几个字节重要）。
 */
@Component
public class GameViewAssembler {

    private final EngineAdvisor engine;
    private final CoachService coach;
    private final GameSessionStore store;

    public GameViewAssembler(EngineAdvisor engine, CoachService coach, GameSessionStore store) {
        this.engine = engine;
        this.coach = coach;
        this.store = store;
    }

    /**
     * 对局状态的完整快照，是所有走子/开局响应的公共部分。
     */
    public Map<String, Object> state(GameSession session) {
        Board board = session.getBoard();
        boolean over = board.isGameOver();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sessionId", session.getId());
        resp.put("playerSide", session.getPlayerSide().name());
        resp.put("board", board.toMatrix());
        resp.put("fen", board.getFen());
        resp.put("turn", board.getTurn().name());
        resp.put("gameOver", over);
        resp.put("winner", board.getWinner() == null ? null : board.getWinner().name());
        resp.put("resultText", session.resultText());
        resp.put("inCheck", !over && board.inCheck(board.getTurn()));
        resp.put("playerInCheck", !over && board.inCheck(session.getPlayerSide()));
        resp.put("moves", session.getMoves());
        resp.put("moveCount", board.moveCount());
        resp.put("fouls", session.getFouls());
        resp.put("chat", session.getMessages());
        resp.put("lastMove", lastMove(board));
        resp.put("engineAvailable", engine.isAvailable());
        resp.put("coachAvailable", coach.isAvailable());
        resp.put("persistenceAvailable", store.isPersistenceAvailable());
        return resp;
    }

    /**
     * 会话不存在（过期或 id 不对）时的统一回应。
     * 前端据此提示用户重开一局。
     */
    public Map<String, Object> noSession(String id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("result", "no_session");
        resp.put("message", "对局不存在或已过期，请重新开局");
        resp.put("sessionId", id);
        resp.put("engineAvailable", engine.isAvailable());
        resp.put("coachAvailable", coach.isAvailable());
        return resp;
    }

    /**
     * 引擎、教练、数据库三件套的体检结果
     */
    public Map<String, Object> systemStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("engineAvailable", engine.isAvailable());
        resp.put("engine", engine.statusText());
        resp.put("coachAvailable", coach.isAvailable());
        // 当前生效的大模型厂商（deepseek / dashscope / none），换厂商时靠它确认有没有切过去
        resp.put("coachProvider", coach.providerName());
        resp.put("coach", coach.statusText());
        resp.put("persistence", store.persistenceStatus());
        resp.put("cachedSessions", store.cachedCount());
        return resp;
    }

    private Map<String, Object> lastMove(Board board) {
        Move m = board.getLastMove();
        if (m == null) return null;
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("fromR", m.from.r);
        info.put("fromC", m.from.c);
        info.put("toR", m.to.r);
        info.put("toC", m.to.c);
        info.put("uci", m.coordToUci());
        return info;
    }
}
