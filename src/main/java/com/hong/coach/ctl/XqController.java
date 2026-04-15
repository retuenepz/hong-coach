// XqController.java
package com.hong.coach.ctl;

import static com.hong.coach.game.XqRules.*;

import com.hong.coach.game.*;
import com.hong.coach.pika.EngineService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/xq")
@CrossOrigin(origins = "*")
public class XqController {

    private final EngineService engine;
    /**
     * 局面缓存
     */
    private HashMap<String,Board> boardContext = new HashMap<>();

    // New: game state tracking
    private boolean gameOver = false;
    private String winner = null;
    private String gameResult = null;

    // New: perpetual check/chase detection
    private final Map<String, Integer> positionCounts = new HashMap<>(); // Position repetition count
    private boolean isRepeatedMove = false;     // Whether in repeated move state

    public XqController(EngineService engine) {
        this.engine = engine;
    }

    /** New game */
    @PostMapping("/new/{id}")
    public Map<String, Object> newGame(@PathVariable String id) {
        Board board = boardContext.get(id);
        if(board != null){
            board = new Board(id);
            boardContext.put(id,board);
        }
        gameOver = false;
        winner = null;
        gameResult = null;
        positionCounts.clear(); // New: clear position count
        isRepeatedMove = false;

        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "new");
        resp.put("turn", board.getTurn());
        resp.put("gameOver", gameOver);
        return resp;
    }

    /** Player move */
    @PostMapping("/move/{id}")
    public Map<String, Object> playerMove(@RequestBody PositionMove move,@PathVariable String id) throws Exception {
        Map<String, Object> resp = new HashMap<>();

        // 是不是已经结束了 将死了还走个屁
        if (gameOver) {
            resp.put("result", "game_over");
            resp.put("gameResult", gameResult);
            resp.put("winner", winner);
            return resp;
        }
        Board board = boardContext.get(id);
        if(board == null){
            boardContext.put(id,board = new Board(id));
        }

        Move m = new Move(move);
        // TODO 总感觉这个逻辑怪怪的 非要这么写么 直接检测 目标走法是否合法不行吗 一定要枚举一遍吗
        List<Move> legal = board.legalMovesAt(m.from);

        boolean isLegal = legal.stream().anyMatch(x -> x.to.equals(m.to));
        if (!isLegal) {
            resp.put("result", "foul");
            resp.put("message", "Illegal move! Please choose a legal move");
            return resp;
        }


        board.makeMove(m);
        String playerMoveUci = m.coordToUci();

        // 检测是否已经将死
        if (board.isCheckMate()) {
            gameOver = true;
//            winner = turn.opponent().toString(); // Opponent wins
//            gameResult = turn.opponent() == Side.RED ? "Red wins (stalemate)" : "Black wins (stalemate)";

            resp.put("result", "game_over");
//            resp.put("gameResult", gameResult);
//            resp.put("winner", winner);
//            resp.put("resultDescription", "Stalemate - " + (turn.opponent() == Side.RED ? "Red" : "Black") + " wins");
            return resp;
        }

        // 走子之后 再次检测棋盘盘面状态
        XqPlayJuge.GameResult gameState = checkGameState();
        if (gameState != XqPlayJuge.GameResult.IN_PROGRESS) {
            handleGameEnd(gameState, resp);
            resp.put("playerMove", playerMoveUci);
            return resp;
        }

        // pikafish 分析一个bestMove
        String bestMoveUci = engine.bestMove(board.getFen(), null);

        // 执行一下bestMove 修改盘面
        if (bestMoveUci != null && !bestMoveUci.isEmpty()) {
            Move aiMove = new Move(bestMoveUci);
            if (aiMove != null) {
                board.makeMove(aiMove);
            }
        }

        // 盘面检测
        gameState = checkGameState();
        if (gameState != XqPlayJuge.GameResult.IN_PROGRESS) {
            handleGameEnd(gameState, resp);
            resp.put("playerMove", playerMoveUci);
            resp.put("aiMove", bestMoveUci);
            return resp;
        }

        // 返回前端
        resp.put("result", "ok");
        resp.put("playerMove", playerMoveUci);
        resp.put("aiMove", bestMoveUci != null ? bestMoveUci : "");
        resp.put("isRepeatedMove", isRepeatedMove);

        // 返回将军的信息
        boolean playerInCheck = XqPlayJuge.isInCheck(board, Side.RED);  // Player plays red
        boolean aiInCheck = XqPlayJuge.isInCheck(board, Side.BLACK);
        resp.put("meInCheck", playerInCheck);
        resp.put("oppInCheck", aiInCheck);

        return resp;
    }

    /**
     * 检测还有没有棋可以走
     * 没棋走了 要么就是绝杀/要么困毙
     */
    private boolean isCheckMate(Board board, Side side) {
        // Traverse all pieces of this side on the board
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null && piece.side == side) {
                    List<Move> legalMoves = board.legalMovesAt(new Pos(r, c));
                    if (!legalMoves.isEmpty()) {
                        return false; // Has legal moves
                    }
                }
            }
        }
        return true; // No legal moves
    }

    /** New: Get board position identifier (for repetition detection) */
    private String getBoardPosition(Board board, Side turn) {
        StringBuilder sb = new StringBuilder();
        sb.append(turn.toString()).append("|");

        // Simplified position representation (piece types and positions)
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 9; c++) {
                Piece piece = board.at(r, c);
                if (piece != null) {
                    sb.append(piece.type.toString()).append(piece.side.toString())
                            .append(r).append(c).append(";");
                }
            }
        }
        return sb.toString();
    }

    /** Check game state */
    private XqPlayJuge.GameResult checkGameState() {
        return null;
    }

    /** Handle game end */
    private void handleGameEnd(XqPlayJuge.GameResult gameState, Map<String, Object> resp) {
        gameOver = true;

        switch (gameState) {
            case RED_WIN:
                winner = "RED";
                gameResult = "Red wins";
                break;
            case BLACK_WIN:
                winner = "BLACK";
                gameResult = "Black wins";
                break;
            case DRAW:
                gameResult = "Draw";
                break;
            default:
                gameResult = "Game ended";
        }

        resp.put("result", "game_over");
        resp.put("gameResult", gameResult);
        resp.put("winner", winner);
        resp.put("resultDescription", XqPlayJuge.getResultDescription(gameState, Side.RED)); // Player plays red
    }

}