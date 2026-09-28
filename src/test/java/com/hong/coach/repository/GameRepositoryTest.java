package com.hong.coach.repository;

import com.hong.coach.game.Side;
import com.hong.coach.repository.entity.ChessChatMessage;
import com.hong.coach.repository.entity.ChessFoul;
import com.hong.coach.repository.entity.ChessMove;
import com.hong.coach.repository.mapper.ChessChatMessageMapper;
import com.hong.coach.repository.mapper.ChessFoulMapper;
import com.hong.coach.repository.mapper.ChessGameMapper;
import com.hong.coach.repository.mapper.ChessMoveMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仓库的降级行为测试。
 * <p>
 * 这是这块代码最重要的一条契约：<b>数据库出任何问题都不能影响下棋</b>。
 * 所以这里专门制造「连接被拒」的场景，验证读返回 null、写静默跳过、绝不上抛异常。
 */
class GameRepositoryTest {

    private ChessGameMapper gameMapper;
    private GameRepository repository;

    @BeforeEach
    void setUp() {
        gameMapper = mock(ChessGameMapper.class);
        repository = new GameRepository(gameMapper, mock(ChessMoveMapper.class),
                mock(ChessFoulMapper.class), mock(ChessChatMessageMapper.class));
    }

    @Test
    @DisplayName("一开始是可用的")
    void availableByDefault() {
        assertTrue(repository.isAvailable());
        assertTrue(repository.statusText().contains("可用"));
    }

    @Test
    @DisplayName("读库失败：返回 null，不抛异常，并标记为不可用")
    void loadFailureIsSwallowed() {
        when(gameMapper.selectById(any())).thenThrow(new RuntimeException("Communications link failure"));

        assertDoesNotThrow(() -> assertNull(repository.load("g1")));
        assertFalse(repository.isAvailable());
        assertTrue(repository.statusText().contains("不可用"));
    }

    @Test
    @DisplayName("写库失败：静默跳过，不抛异常")
    void saveFailureIsSwallowed() {
        doThrow(new RuntimeException("Communications link failure"))
                .when(gameMapper).selectById("g1");

        GameSnapshot snapshot = new GameSnapshot("g1", Side.RED, false, null, "initial-fen", "fen", Side.RED,
                List.of(new ChessMove(1, Side.RED, "h2e2", "炮二平五", false)),
                List.of(new ChessFoul(2, "第 2 手违规")),
                List.of(ChessChatMessage.coach("开局", "开局")));

        assertDoesNotThrow(() -> repository.save(snapshot));
        assertFalse(repository.isAvailable());
    }

    @Test
    @DisplayName("启动建表失败时，状态要如实反映为不可用")
    void reportedUnavailableIsReflectedInStatus() {
        repository.reportUnavailable("Failed to execute database script");

        assertFalse(repository.isAvailable());
        assertTrue(repository.statusText().contains("不可用"));
        assertTrue(repository.statusText().contains("Failed to execute database script"));
    }

    @Test
    @DisplayName("已经确认不可用后，短时间内不再去碰数据库（避免每次走子都等连接超时）")
    void skipsDatabaseWhileDown() {
        when(gameMapper.selectById(any())).thenThrow(new RuntimeException("down"));
        repository.load("g1");     // 第一次失败，进入熔断

        assertNull(repository.load("g1"));
        assertDoesNotThrow(() -> repository.save(new GameSnapshot("g1", Side.RED, false, null,
                "initial-fen", "fen", Side.RED, List.of(), List.of(), List.of())));
        // 熔断期间连查询都不应该发生：只失败过一次
        verify(gameMapper, times(1)).selectById(any());
    }
}
