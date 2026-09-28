package com.hong.coach.repository;

import com.hong.coach.game.Side;
import com.hong.coach.repository.entity.ChessChatMessage;
import com.hong.coach.repository.entity.ChessFoul;
import com.hong.coach.repository.entity.ChessMove;

import java.util.List;

/**
 * 一局对战在数据库里的完整快照。
 * <p>
 * 刻意设计成不依赖 service 包的中立结构，这样 {@code repository} 不必反过来引入
 * 对局会话类型，两层的依赖方向保持单向：service → repository。
 * <p>
 * {@code initialFen}（开局时的局面）与 {@code fen}（当前局面）缺一不可：前者是重放着法的
 * 起点，用户摆过棋的对局靠它才能正确读档；后者只是重放完的自检依据。
 */
public record GameSnapshot(
        String id,
        Side playerSide,
        boolean gameOver,
        Side winner,
        String initialFen,
        String fen,
        Side turn,
        List<ChessMove> moves,
        List<ChessFoul> fouls,
        List<ChessChatMessage> messages
) {
}
