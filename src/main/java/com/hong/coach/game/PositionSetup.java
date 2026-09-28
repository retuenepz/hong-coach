package com.hong.coach.game;

import com.hong.coach.game.XqRules.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户摆出来的局面：前端可以交来 10×9 的棋子矩阵，也可以交来一段 FEN，二选一。
 * <p>
 * 前端两种入口（棋盘上点着摆、或者直接粘贴一段局面文本）最终都收敛到这里，
 * 由服务端统一解析并检查，界面自己不需要懂任何象棋规则。
 * <p>
 * {@link #place} 是另一条更细的入口：<b>落子之前</b>问一句「这一枚子能不能放在这个点上」。
 * 界面据此把不合规的摆放直接挡下来，而不是先摆上去、再报一堆错让用户自己挪。
 */
public final class PositionSetup {

    /**
     * 摆棋结果。
     *
     * @param board    解析出来的棋盘；{@code null} 表示结构都没解析成功（例如行数不对）
     * @param problems 问题清单（纯文本），空表示可以拿去下棋
     * @param details  同上，但每条带着盘上的位置，界面据此把出错的格子圈出来
     */
    public record Built(Board board, List<String> problems, List<FenParser.Problem> details) {
        public boolean ok() {
            return board != null && problems.isEmpty();
        }
    }

    /**
     * 落子前的判断结果。
     *
     * @param ok      这一枚子放得下去吗
     * @param message 放不下去的原因，一句话，可直接显示在界面上；放得下时为 {@code null}
     * @param details 同上，带位置，界面可把出错的格子圈出来
     */
    public record Placement(boolean ok, String message, List<FenParser.Problem> details) {
        public static Placement allowed() {
            return new Placement(true, null, List.of());
        }

        public static Placement refused(String message) {
            return new Placement(false, message, List.of(FenParser.Problem.of(message)));
        }
    }

    private PositionSetup() {
    }

    /**
     * 按前端提交的内容造一个棋盘：优先用棋子矩阵，没有矩阵时再解析 FEN。
     *
     * @param turn 轮到谁走；为 {@code null} 时用 FEN 里写的（矩阵没有这个信息，默认红方先行）
     */
    public static Built build(String id, List<List<String>> matrix, String fen, Side turn) {
        if (matrix != null && !matrix.isEmpty()) {
            try {
                Board board = new Board(id).reset();
                applyMatrix(board, matrix);
                board.setTurn(turn == null ? Side.RED : turn);
                return inspect(board);
            } catch (IllegalArgumentException e) {
                return broken(e.getMessage());
            }
        }
        if (fen != null && !fen.isBlank()) {
            try {
                Board board = FenParser.parse(id, fen);
                // 前端显式指定的走子方优先于 FEN 里的写法
                if (turn != null) board.setTurn(turn);
                return inspect(board);
            } catch (IllegalArgumentException e) {
                return broken(e.getMessage());
            }
        }
        return broken("没有收到盘面：请给出棋子矩阵，或一段 FEN");
    }

    /**
     * 落子之前问一句：把 {@code code} 放在第 {@code r} 行第 {@code c} 列，摆得下去吗？
     * <p>
     * 盘面用前端交上来的整个矩阵重建（用户每摆一格都会把全盘带上），再把这一枚子放进去试，
     * 由 {@link FenParser#problemsAt} 只看跟这一枚子有关的规则。
     * {@code code} 为空表示擦除 —— 拿走一枚子只会让局面变简单，一律放行。
     *
     * @return 放得下时 {@code ok=true}；放不下时给出原因，界面据此拒绝这一次摆放
     */
    public static Placement place(String id, List<List<String>> matrix, Integer r, Integer c, String code) {
        if (r == null || c == null) return Placement.refused("没有收到落点");
        if (matrix == null || matrix.isEmpty()) return Placement.refused("没有收到盘面");
        Board board = new Board(id).reset();
        try {
            applyMatrix(board, matrix);
        } catch (IllegalArgumentException e) {
            return Placement.refused(e.getMessage());
        }
        if (code == null || code.isBlank()) return Placement.allowed();
        if (!board.in(r, c)) return Placement.refused("落点不在棋盘上");
        board.set(r, c, FenParser.pieceAt(code, r, c));

        List<FenParser.Problem> problems = FenParser.problemsAt(board, new Pos(r, c));
        if (problems.isEmpty()) return Placement.allowed();
        return new Placement(false, problems.get(0).text(), problems);
    }

    /** 把校验结果同时整理成「纯文本」和「带位置」两份，界面按需取用 */
    private static Built inspect(Board board) {
        List<FenParser.Problem> details = FenParser.diagnose(board);
        List<String> texts = new ArrayList<>(details.size());
        for (FenParser.Problem p : details) texts.add(p.text());
        return new Built(board, List.copyOf(texts), details);
    }

    /** 结构性错误（行数、列数、符号）没有具体格子，只能给一句话 */
    private static Built broken(String message) {
        return new Built(null, List.of(message), List.of(FenParser.Problem.of(message)));
    }

    private static void applyMatrix(Board board, List<List<String>> matrix) {
        if (matrix.size() != FenParser.ROWS) {
            throw new IllegalArgumentException(
                    "盘面应该有 " + FenParser.ROWS + " 行，现在是 " + matrix.size() + " 行");
        }
        for (int r = 0; r < FenParser.ROWS; r++) {
            List<String> row = matrix.get(r);
            if (row == null || row.size() != FenParser.COLS) {
                throw new IllegalArgumentException("第 " + (r + 1) + " 行应该有 " + FenParser.COLS
                        + " 列，现在是 " + (row == null ? 0 : row.size()) + " 列");
            }
            for (int c = 0; c < FenParser.COLS; c++) {
                board.set(r, c, FenParser.pieceAt(row.get(c), r, c));
            }
        }
    }
}
