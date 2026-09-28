package com.hong.coach.service;

import com.hong.coach.game.Board;
import com.hong.coach.game.FenParser;
import com.hong.coach.game.Move;
import com.hong.coach.game.Side;
import com.hong.coach.repository.GameRepository;
import com.hong.coach.repository.GameSnapshot;
import com.hong.coach.repository.entity.ChessMove;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对局会话仓库：内存缓存 + 数据库落盘。
 * <p>
 * 缓存与数据库的分工：
 * <ul>
 *   <li><b>内存</b>是热路径。正在下的一局连同引擎分析缓存都留在内存里，走子不用读库。</li>
 *   <li><b>数据库</b>是可选的持久化层。服务重启、或会话被 TTL 回收之后，
 *       靠它把棋局重新装回来，用户换个时间打开页面还在原来的局面上。</li>
 * </ul>
 * 读档的做法是「重放」而不是「回填盘面」：先用 {@code initial_fen} 摆出开局局面，
 * 再把着法序列按序走一遍。好处是只依赖 {@code Board} 的走子语义（确定性的），
 * 规则有调整也不会读出错误的盘面；用户摆过棋的对局也因为记了开局局面而能正确还原。
 * 快照里的 {@code fen} 只用来做一次自检比对。
 */
@Slf4j
@Service
public class GameSessionStore {

    /** 会话在内存里闲置多久后回收（回收后可随时从数据库重新装回来） */
    private static final long SESSION_TTL_MS = 6 * 60 * 60 * 1000L;

    /** 内存里最多同时保留多少局，超出按最久未活动淘汰 */
    private static final int MAX_CACHED_SESSIONS = 300;

    private final Map<String, GameSession> sessions = new ConcurrentHashMap<>();
    private final GameRepository repository;

    public GameSessionStore(GameRepository repository) {
        this.repository = repository;
    }

    // ==================== 读写会话 ====================

    /**
     * 新建一局标准开局的棋放进缓存（同一 id 会被直接覆盖，用于重开）。
     */
    public GameSession create(String id, Side playerSide) {
        Board board = new Board(id);
        return cache(new GameSession(id, board, board.getFen()), playerSide);
    }

    /**
     * 用用户自己摆好的局面开一局。
     * <p>
     * 传进来的 {@code board} 已经被 {@code PositionSetup} 校验过；这里只负责记住
     * 「从哪个局面开始的」，读档时才摆得回来。
     */
    public GameSession create(String id, Board board, Side playerSide) {
        return cache(new GameSession(id, board, board.getFen()), playerSide);
    }

    private GameSession cache(GameSession session, Side playerSide) {
        session.setPlayerSide(playerSide);
        sessions.put(session.getId(), session);
        evictOverflow();
        return session;
    }

    /**
     * 取会话：内存命中最快；没命中就回源数据库重放出来；都没有则返回 null。
     */
    public GameSession get(String id) {
        if (id == null || id.isBlank()) return null;
        GameSession cached = sessions.get(id);
        if (cached != null) return cached;

        GameSession restored = restoreFromDatabase(id);
        if (restored == null) return null;
        sessions.put(id, restored);
        evictOverflow();
        log.info("从数据库恢复对局 {}（{} 手，用户执{}）",
                id, restored.getMoves().size(), restored.getPlayerSide());
        return restored;
    }

    /**
     * 把会话写回数据库。数据库不可用时静默跳过，不影响对局。
     */
    public void persist(GameSession session) {
        if (session == null) return;
        repository.save(snapshotOf(session));
    }

    public int cachedCount() {
        return sessions.size();
    }

    public boolean isPersistenceAvailable() {
        return repository.isAvailable();
    }

    public String persistenceStatus() {
        return repository.statusText();
    }

    // ==================== 存档 / 读档 ====================

    /**
     * 会话 → 快照。
     * <p>
     * 注意这里把着法、违规、对话三个列表直接交给了仓库：仓库写入前会往这些对象上
     * 盖 {@code id / gameId / createdAt} 三个「只在库里有意义」的字段（都标了
     * {@code @JsonIgnore}，不参与接口输出），因此调用方不需要先做一次深拷贝。
     */
    private GameSnapshot snapshotOf(GameSession session) {
        Board board = session.getBoard();
        return new GameSnapshot(
                session.getId(),
                session.getPlayerSide(),
                board.isGameOver(),
                board.getWinner(),
                session.getInitialFen(),
                board.getFen(),
                board.getTurn(),
                session.getMoves(),
                session.getFouls(),
                session.getMessages());
    }

    /**
     * 快照 → 会话：先摆出开局局面，再按顺序重放着法还原盘面。
     * <p>
     * 遇到走不下去的着法就停在那里并告警 —— 宁可少几步，也不能让异常数据把整局丢掉。
     * 但开局局面本身解析不了是另一回事：那时重放出来的盘面会整个不对，
     * 与其给用户一副错棋，不如当作这局不存在。
     */
    private GameSession restoreFromDatabase(String id) {
        GameSnapshot snapshot = repository.load(id);
        if (snapshot == null) return null;

        Board board;
        String initialFen = snapshot.initialFen();
        try {
            if (initialFen == null || initialFen.isBlank()) {
                // 这个字段是后加的，老数据的开局就是标准局面
                board = new Board(id);
                initialFen = board.getFen();
            } else {
                board = FenParser.parse(id, initialFen);
            }
        } catch (Exception e) {
            log.warn("读档时开局局面解析失败，只能当作这局不存在 session={} initialFen={}",
                    id, initialFen, e);
            return null;
        }

        List<ChessMove> ordered = snapshot.moves().stream()
                .sorted(Comparator.comparingInt(ChessMove::getMoveNo))
                .toList();
        // 收到重放成功的那些手，而不是全部 ordered：截断时两者长度必须一致。
        // 棋谱与棋盘的走法栈是平行的两套结构，悔棋各自弹一个 —— 一旦长度错位，
        // 悔出来的盘面就和棋谱对不上了。
        List<ChessMove> replayed = new ArrayList<>(ordered.size());
        for (ChessMove stored : ordered) {
            try {
                Move move = new Move(stored.getUci());
                if (!board.isLegal(move) || !board.makeMove(move)) {
                    log.warn("读档时着法走不下去，在此截断 session={} 第 {} 手 {}",
                            id, stored.getMoveNo(), stored.getUci());
                    break;
                }
                replayed.add(stored);
            } catch (Exception e) {
                log.warn("读档时解析着法失败，在此截断 session={} 第 {} 手 {}", id, stored.getMoveNo(), stored.getUci());
                break;
            }
        }
        if (snapshot.fen() != null && !snapshot.fen().equals(board.getFen())) {
            log.warn("读档后盘面与快照不一致，以重放结果为准 session={} 快照={} 重放={}",
                    id, snapshot.fen(), board.getFen());
        }

        GameSession session = new GameSession(id, board, initialFen);
        session.setPlayerSide(snapshot.playerSide());
        session.getMoves().addAll(replayed);
        session.getFouls().addAll(snapshot.fouls());
        session.getMessages().addAll(snapshot.messages());
        return session;
    }

    // ==================== 淘汰 ====================

    /**
     * 先按闲置时间回收，再兜住数量上限。被淘汰的只是内存副本，
     * 下次访问会重新从数据库装回来。
     */
    private void evictOverflow() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(e -> now - e.getValue().getUpdatedAt() > SESSION_TTL_MS);
        int overflow = sessions.size() - MAX_CACHED_SESSIONS;
        if (overflow <= 0) return;
        sessions.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().getUpdatedAt()))
                .limit(overflow)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(sessions::remove);
    }
}
