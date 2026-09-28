package com.hong.coach.engine;

import com.hong.coach.game.Board;
import com.hong.coach.game.Move;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎对接的端到端测试。
 * <p>
 * 这里刻意不用 {@code @SpringBootTest}，直接手工构造 EngineService，
 * 这样既验证了「从 classpath 释放内置引擎」的兜底逻辑，也不依赖数据库。
 */
class EngineServiceTest {

    private static EngineService engineService;

    @BeforeAll
    static void setUp() {
        EngineProperties props = new EngineProperties();
        // 留空即走 classpath 内置引擎（resources/engine）
        props.setPath(System.getenv("ENGINE_PATH"));
        props.setPoolSize(1);
        props.setPlayMovetime(300);
        props.setAnalyzeMovetime(500);
        props.setMultiPv(3);
        engineService = new EngineService(props);
        engineService.start();
    }

    @AfterAll
    static void tearDown() {
        if (engineService != null) engineService.stop();
    }

    @Test
    @DisplayName("引擎能启动并被定位到")
    void engineIsAvailable() {
        assertTrue(engineService.isAvailable(), "引擎不可用：" + engineService.statusText());
    }

    @Test
    @DisplayName("开局局面能拿到合法的推荐着法")
    void bestMoveOnStartPosition() {
        Board board = new Board("t");
        String best = engineService.bestMove(board.getFen());
        assertNotNull(best);
        assertFalse(best.isBlank());
        assertTrue(board.isLegal(new Move(best)), "引擎返回的 " + best + " 在开局盘面上不合法");
    }

    @Test
    @DisplayName("分析结果包含评分、深度与主变")
    void analysisHasScoreAndPv() {
        Board board = new Board("t");
        EngineAnalysis analysis = engineService.analyzeForReview(board.getFen());

        assertNotNull(analysis.getBestMove());
        assertTrue(analysis.getScoreCp() != null || analysis.getScoreMate() != null, "应当拿到评分");
        assertTrue(analysis.getDepth() > 0, "应当拿到搜索深度");
        assertTrue(analysis.getCostMs() > 0);

        List<String> pv = analysis.principalVariation();
        assertFalse(pv.isEmpty(), "应当拿到主变");
        assertTrue(board.isLegal(new Move(pv.get(0))), "主变第一手 " + pv.get(0) + " 应当合法");
    }

    @Test
    @DisplayName("能把中局局面（由着法历史生成的 FEN）正确交给引擎")
    void bestMoveOnMidgamePosition() {
        Board board = new Board("t");
        board.makeMove(new Move("h2e2")); // 炮二平五
        board.makeMove(new Move("h9g7")); // 马8进7
        board.makeMove(new Move("b0c2")); // 马八进七

        String fen = board.getFen();
        assertEquals("b", fen.split(" ")[1], "走了三步之后应当轮到黑方");
        assertEquals(3, board.moveCount());

        String best = engineService.bestMove(fen);
        assertNotNull(best);
        assertTrue(board.isLegal(new Move(best)), "引擎返回的 " + best + " 不合法，fen=" + fen);
    }

    @Test
    @DisplayName("多路主变都能解析出来")
    void multiPvParsed() {
        EngineAnalysis analysis = engineService.analyze(new Board("t").getFen(), 400, 3);
        assertFalse(analysis.getPvLines().isEmpty());
        assertTrue(analysis.getPvLines().size() <= 3);
        analysis.getPvLines().forEach(line -> {
            assertNotNull(line.getMoves());
            assertTrue(line.getScoreCp() != null || line.getScoreMate() != null,
                    "主变 " + line.getMultipv() + " 应当带评分");
        });
    }

    @Test
    @DisplayName("连续两次分析都能拿到合法着法")
    void repeatedAnalysis() {
        Board board = new Board("t");
        String fen = board.getFen();
        for (int i = 0; i < 2; i++) {
            EngineAnalysis analysis = engineService.analyze(fen, 300, 1);
            assertTrue(board.isLegal(new Move(analysis.getBestMove())),
                    "第 " + (i + 1) + " 次分析返回的着法不合法");
        }
    }
}
