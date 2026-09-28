package com.hong.coach.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hong.coach.game.Side;
import com.hong.coach.repository.entity.ChessChatMessage;
import com.hong.coach.repository.entity.ChessFoul;
import com.hong.coach.repository.entity.ChessGame;
import com.hong.coach.repository.entity.ChessMove;
import com.hong.coach.repository.mapper.ChessChatMessageMapper;
import com.hong.coach.repository.mapper.ChessFoulMapper;
import com.hong.coach.repository.mapper.ChessGameMapper;
import com.hong.coach.repository.mapper.ChessMoveMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 对局仓库：把一局对战整份读进来、整份写回去。
 * <p>
 * 两条刻意的设计取舍：
 * <ul>
 *   <li><b>整局覆盖写入</b>。每次保存先清空该局的子表再全量插入。对局数据量很小
 *       （着法以百计），但换来的是天然幂等 —— 不用维护「哪些已存过」的增量状态，
 *       程序在中途崩溃也不会写出半截数据。</li>
 *   <li><b>数据库是可选的</b>。这里所有方法都不抛异常：连不上库或写失败时打日志并
 *       降级为「仅内存对局」，绝不能因为数据库把下棋流程卡住。同时用一个简单的熔断
 *       （失败后 30 秒内不再尝试）避免每次走子都去等一遍连接超时。</li>
 * </ul>
 */
@Slf4j
@Repository
public class GameRepository {

    /** 写失败后多久再试探一次数据库 */
    private static final long RETRY_INTERVAL_MS = 60_000L;

    private final ChessGameMapper gameMapper;
    private final ChessMoveMapper moveMapper;
    private final ChessFoulMapper foulMapper;
    private final ChessChatMessageMapper chatMapper;

    private volatile boolean available = true;
    private volatile long retryAfter = 0L;
    private volatile String lastError;

    public GameRepository(ChessGameMapper gameMapper,
                          ChessMoveMapper moveMapper,
                          ChessFoulMapper foulMapper,
                          ChessChatMessageMapper chatMapper) {
        this.gameMapper = gameMapper;
        this.moveMapper = moveMapper;
        this.foulMapper = foulMapper;
        this.chatMapper = chatMapper;
    }

    // ==================== 状态 ====================

    public boolean isAvailable() {
        return available;
    }

    /**
     * 由外部（启动建表）告知数据库当前不可用。
     * <p>
     * 没有这一条的时候，状态会一直显示「可用」，直到第一次真正写库失败才纠正过来 ——
     * 前端就会先给用户一个错误的安心提示。启动阶段既然已经探到了，就直接记下来。
     */
    public void reportUnavailable(String reason) {
        lastError = reason;
        available = false;
        retryAfter = System.currentTimeMillis() + RETRY_INTERVAL_MS;
    }

    public String statusText() {
        if (available) return "可用";
        return "不可用（已降级为仅内存，原因：" + (lastError == null ? "未知" : lastError) + "）";
    }

    // ==================== 读 ====================

    /**
     * 按 id 读取一局。库里没有、或数据库不可用时返回 null。
     */
    public GameSnapshot load(String id) {
        if (id == null || id.isBlank() || skipBecauseDown()) return null;
        try {
            ChessGame row = gameMapper.selectById(id);
            if (row == null) {
                markSuccess();
                return null;
            }
            List<ChessMove> moves = moveMapper.selectList(Wrappers.<ChessMove>lambdaQuery()
                    .eq(ChessMove::getGameId, id)
                    .orderByAsc(ChessMove::getMoveNo));
            List<ChessFoul> fouls = foulMapper.selectList(Wrappers.<ChessFoul>lambdaQuery()
                    .eq(ChessFoul::getGameId, id)
                    .orderByAsc(ChessFoul::getId));
            List<ChessChatMessage> messages = chatMapper.selectList(Wrappers.<ChessChatMessage>lambdaQuery()
                    .eq(ChessChatMessage::getGameId, id)
                    .orderByAsc(ChessChatMessage::getId));
            markSuccess();
            return new GameSnapshot(
                    row.getId(),
                    parseSide(row.getPlayerSide(), Side.RED),
                    "END".equalsIgnoreCase(row.getStatus()),
                    parseSide(row.getWinner(), null),
                    row.getInitialFen(),
                    row.getFen(),
                    parseSide(row.getTurn(), Side.RED),
                    moves,
                    fouls,
                    messages);
        } catch (Exception e) {
            markFailure(e);
            return null;
        }
    }

    // ==================== 写 ====================

    /**
     * 整份覆盖保存一局。数据库不可用时静默跳过。
     */
    public void save(GameSnapshot snapshot) {
        if (snapshot == null || snapshot.id() == null || snapshot.id().isBlank() || skipBecauseDown()) return;
        try {
            LocalDateTime now = LocalDateTime.now();
            writeHeader(snapshot, now);
            writeMoves(snapshot, now);
            writeFouls(snapshot, now);
            writeMessages(snapshot, now);
            markSuccess();
        } catch (Exception e) {
            markFailure(e);
        }
    }

    private void writeHeader(GameSnapshot s, LocalDateTime now) {
        ChessGame row = new ChessGame();
        row.setId(s.id());
        row.setPlayerSide(s.playerSide() == null ? null : s.playerSide().name());
        row.setStatus(s.gameOver() ? "END" : "PLAYING");
        row.setWinner(s.winner() == null ? null : s.winner().name());
        row.setInitialFen(s.initialFen());
        row.setFen(s.fen());
        row.setTurn(s.turn() == null ? null : s.turn().name());
        row.setMoveCount(s.moves().size());

        ChessGame existing = gameMapper.selectById(s.id());
        if (existing == null) {
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            gameMapper.insert(row);
        } else {
            row.setCreatedAt(existing.getCreatedAt());
            row.setUpdatedAt(now);
            gameMapper.updateById(row);
        }
    }

    private void writeMoves(GameSnapshot s, LocalDateTime now) {
        moveMapper.delete(Wrappers.<ChessMove>lambdaQuery().eq(ChessMove::getGameId, s.id()));
        for (ChessMove m : s.moves()) {
            m.setId(null);
            m.setGameId(s.id());
            m.setCreatedAt(now);
            moveMapper.insert(m);
        }
    }

    private void writeFouls(GameSnapshot s, LocalDateTime now) {
        foulMapper.delete(Wrappers.<ChessFoul>lambdaQuery().eq(ChessFoul::getGameId, s.id()));
        for (ChessFoul f : s.fouls()) {
            f.setId(null);
            f.setGameId(s.id());
            f.setCreatedAt(now);
            foulMapper.insert(f);
        }
    }

    private void writeMessages(GameSnapshot s, LocalDateTime now) {
        chatMapper.delete(Wrappers.<ChessChatMessage>lambdaQuery().eq(ChessChatMessage::getGameId, s.id()));
        for (ChessChatMessage c : s.messages()) {
            c.setId(null);
            c.setGameId(s.id());
            c.setCreatedAt(now);
            chatMapper.insert(c);
        }
    }

    // ==================== 熔断 ====================

    private boolean skipBecauseDown() {
        return !available && System.currentTimeMillis() < retryAfter;
    }

    private void markSuccess() {
        if (!available) log.info("数据库已恢复，对局重新开始落库");
        available = true;
        retryAfter = 0L;
        lastError = null;
    }

    private void markFailure(Exception e) {
        lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        retryAfter = System.currentTimeMillis() + RETRY_INTERVAL_MS;
        if (available) {
            log.warn("数据库不可用，对局暂时只存在内存里（{} 秒后重试）：{}", RETRY_INTERVAL_MS / 1000, lastError);
        }
        available = false;
    }

    private static Side parseSide(String name, Side fallback) {
        if (name == null) return fallback;
        try {
            return Side.valueOf(name.trim().toUpperCase());
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 仅测试与诊断使用：返回当前已保存的最大着法号 */
    public List<ChessMove> movesOf(String id) {
        if (id == null || skipBecauseDown()) return new ArrayList<>();
        try {
            return moveMapper.selectList(Wrappers.<ChessMove>lambdaQuery()
                    .eq(ChessMove::getGameId, id)
                    .orderByAsc(ChessMove::getMoveNo));
        } catch (Exception e) {
            markFailure(e);
            return new ArrayList<>();
        }
    }
}
