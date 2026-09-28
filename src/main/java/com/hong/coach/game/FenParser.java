package com.hong.coach.game;

import com.hong.coach.game.XqRules.Piece;
import com.hong.coach.game.XqRules.PieceType;
import com.hong.coach.game.XqRules.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 局面文本（FEN）与 {@link Board} 之间的转换，以及「这个局面能不能拿来下」的检查。
 * <p>
 * 摆棋功能的两块地基：
 * <ul>
 *   <li>{@link #parse} —— 把一段 FEN 变成棋盘。结构不对时抛异常，消息是给用户看的中文，
 *       可以直接显示在界面上。</li>
 *   <li>{@link #problems} —— 语义检查。FEN 形状合法不代表局面能下，这里列出所有
 *       「摆不出来 / 摆出来也没法下」的问题，一次全给出来，用户好一次性改完。</li>
 * </ul>
 * 校验刻意只覆盖能被一眼看出的硬错误（将帅、九宫、过河、兵卒后退、照面、被将），
 * 不追求把棋规抠到底 —— 一副残局是不是「理论上必胜」不是这里该管的事。
 * <p>
 * 行号在给用户的提示里一律用 1~10（从上往下数），和界面上看到的顺序一致。
 */
public final class FenParser {

    /** 棋盘尺寸：10 行 9 列 */
    public static final int ROWS = 10;
    public static final int COLS = 9;

    /** 各兵种单方最多能有几个 */
    private static final Map<PieceType, Integer> MAX_COUNT = Map.of(
            PieceType.GENERAL, 1,
            PieceType.ADVISOR, 2,
            PieceType.ELEPHANT, 2,
            PieceType.HORSE, 2,
            PieceType.ROOK, 2,
            PieceType.CANNON, 2,
            PieceType.PAWN, 5);

    private FenParser() {
    }

    // ==================== FEN -> 棋盘 ====================

    /**
     * 解析 FEN 得到棋盘。只认前两个字段（盘面、轮到谁走），后面的字段（易位、回合数等）
     * 中国象棋用不上，有就忽略、没有也不报错。
     *
     * @throws IllegalArgumentException 结构不合法，消息可直接展示给用户
     */
    public static Board parse(String id, String fen) {
        if (fen == null || fen.isBlank()) {
            throw new IllegalArgumentException("局面文本是空的");
        }
        String[] parts = fen.trim().split("\\s+");
        Board board = new Board(id).reset();
        fillFromPlacement(board, parts[0]);
        board.setTurn(parseTurn(parts.length > 1 ? parts[1] : null));
        return board;
    }

    private static void fillFromPlacement(Board board, String placement) {
        String[] rows = placement.split("/", -1);
        if (rows.length != ROWS) {
            throw new IllegalArgumentException("局面应该有 " + ROWS + " 行，现在是 " + rows.length + " 行");
        }
        for (int r = 0; r < ROWS; r++) {
            int c = 0;
            for (char ch : rows[r].toCharArray()) {
                if (ch >= '1' && ch <= '9') {
                    c += ch - '0';
                    continue;
                }
                PieceType type = typeOf(ch);
                if (type == null) {
                    throw new IllegalArgumentException(
                            "第 " + (r + 1) + " 行出现了不认识的符号「" + ch + "」");
                }
                if (c >= COLS) {
                    throw new IllegalArgumentException("第 " + (r + 1) + " 行超过了 " + COLS + " 列");
                }
                board.set(r, c, XqRules.create(type, sideOf(ch), new Pos(r, c)));
                c++;
            }
            if (c != COLS) {
                throw new IllegalArgumentException(
                        "第 " + (r + 1) + " 行只有 " + c + " 列，应该是 " + COLS + " 列");
            }
        }
    }

    private static Side parseTurn(String text) {
        if (text == null) return Side.RED;
        if (text.startsWith("b") || text.startsWith("B")) return Side.BLACK;
        return Side.RED;
    }

    // ==================== 字符 <-> 棋子 ====================

    /** FEN 里的棋子字母：大写红方、小写黑方；不认识返回 null */
    public static PieceType typeOf(char ch) {
        switch (Character.toLowerCase(ch)) {
            case 'r': return PieceType.ROOK;
            case 'n': return PieceType.HORSE;
            case 'b': return PieceType.ELEPHANT;
            case 'a': return PieceType.ADVISOR;
            case 'k': return PieceType.GENERAL;
            case 'c': return PieceType.CANNON;
            case 'p': return PieceType.PAWN;
            default: return null;
        }
    }

    private static Side sideOf(char ch) {
        return Character.isUpperCase(ch) ? Side.RED : Side.BLACK;
    }

    /**
     * 前端盘面矩阵里的一格（同 FEN 的字母约定）转成棋子；空格与不认识的符号返回 null。
     */
    public static Piece pieceAt(String code, int row, int col) {
        if (code == null || code.isBlank()) return null;
        char ch = code.trim().charAt(0);
        PieceType type = typeOf(ch);
        if (type == null) return null;
        return XqRules.create(type, sideOf(ch), new Pos(row, col));
    }

    // ==================== 局面检查 ====================

    /**
     * 一条问题：给用户看的说明，外加盘上对应的位置。
     * <p>
     * 带上位置是为了让界面能把出错的地方圈出来 —— 只说「第 9 行第 7 列不行」，
     * 用户还得自己数格子。
     *
     * @param cells 相关位置；结构性问题（例如行数不对）没有具体格子，为空列表
     */
    public record Problem(String text, List<Pos> cells) {
        public static Problem of(String text) {
            return new Problem(text, List.of());
        }

        public static Problem at(String text, Pos... cells) {
            return new Problem(text, List.of(cells));
        }
    }

    /**
     * 列出这个局面的所有问题，空列表表示可以拿去下棋。
     */
    public static List<String> problems(Board board) {
        return diagnose(board).stream().map(Problem::text).toList();
    }

    /**
     * 同上，但每条问题都带着盘上的位置，供界面高亮。
     */
    public static List<Problem> diagnose(Board board) {
        List<Problem> out = new ArrayList<>();
        if (board == null) {
            out.add(Problem.of("没有盘面"));
            return out;
        }
        checkLocal(board, out);
        // 「没有帅 / 没有将」这条提示在 checkLocal 里给，所以要先跑再判断齐不齐
        if (!hasBothGenerals(board)) {
            // 将帅都不齐，后面那些「谁将谁、谁没得走」的判断都无从谈起
            return out;
        }
        checkGeneralsNotFacing(board, out);
        checkOpponentNotInCheck(board, out);
        checkSideToMoveCanMove(board, out);
        return out;
    }

    /**
     * 列出「把某一枚子摆在这个点上」会踩到的规则，空列表表示这个位置摆得下去。
     * <p>
     * 摆棋时用它做<b>落子之前</b>的判断：数量、九宫、仕的斜线点、相的田字点、兵卒的方向，
     * 这几条规则只看「这枚子摆在哪」，所以不必等整盘摆完就能判。
     * <p>
     * 「两将照面」「轮到的一方无棋可走」这类要通盘看的事不在这里 ——
     * 摆到一半的盘面本来就不完整，拿它当把门条件只会让用户什么子都摆不下去。
     * 那些留给 {@link #diagnose}，等整盘摆完再报。
     *
     * @param board 已经包含这一枚子的盘面（调用方负责先把子放上去）
     * @param pos   这一枚子的位置
     */
    public static List<Problem> problemsAt(Board board, Pos pos) {
        List<Problem> hit = new ArrayList<>();
        if (board == null || pos == null) return hit;
        List<Problem> all = new ArrayList<>();
        checkLocal(board, all);
        for (Problem p : all) {
            for (Pos q : p.cells()) {
                if (q.r == pos.r && q.c == pos.c) {
                    hit.add(p);
                    break;
                }
            }
        }
        return hit;
    }

    /**
     * 只跟「某一枚子摆在哪」有关的规则，是落子前预判能覆盖的全部内容。
     */
    private static void checkLocal(Board board, List<Problem> out) {
        checkCounts(board, out);
        checkGeneralInPalace(board, out);
        checkAdvisors(board, out);
        checkElephants(board, out);
        checkPawnsNotRetreated(board, out);
    }

    /** 各兵种数量不能超过上限（将帅必须是恰好一个） */
    private static void checkCounts(Board board, List<Problem> out) {
        for (Map.Entry<PieceType, Integer> e : MAX_COUNT.entrySet()) {
            PieceType type = e.getKey();
            int max = e.getValue();
            for (Side side : Side.values()) {
                List<Pos> positions = positionsOf(board, side, type);
                if (positions.size() > max) {
                    out.add(new Problem(sideName(side) + "有 " + positions.size() + " 个"
                            + nameOf(type, side) + "，最多只能有 " + max + " 个", positions));
                }
            }
        }
    }

    private static boolean hasBothGenerals(Board board) {
        boolean both = true;
        for (Side side : Side.values()) {
            if (positionsOf(board, side, PieceType.GENERAL).isEmpty()) {
                both = false;
            }
        }
        return both;
    }

    /**
     * 将帅必须在<b>自己那一方</b>的九宫里。
     * <p>
     * 提示要把「现在摆在第几行第几列」和「自己那半边是哪几行哪几列」都写出来 ——
     * 只说一句「不在九宫里」，用户看着盘上明明在九宫里的子会一头雾水：
     * 红帅摆到黑方九宫（第 1~3 行第 4~6 列）同样「在九宫里」，但那是别人的九宫。
     */
    private static void checkGeneralInPalace(Board board, List<Problem> out) {
        for (Side side : Side.values()) {
            List<Pos> generals = positionsOf(board, side, PieceType.GENERAL);
            String name = nameOf(PieceType.GENERAL, side);
            if (generals.isEmpty()) {
                out.add(Problem.of(sideName(side) + "没有" + name));
                continue;
            }
            Pos g = generals.get(0);
            if (board.inPalace(side, g.r, g.c)) continue;

            String at = "第 " + (g.r + 1) + " 行第 " + (g.c + 1) + " 列";
            if (board.inPalace(side.opponent(), g.r, g.c)) {
                // 这一档单列出来：子确实在「一个」九宫里，只是是对方那个
                out.add(Problem.at(sideName(side) + "的" + name + "摆在了" + sideName(side.opponent())
                        + "的九宫里（" + at + "）：" + name + "只能待在自己这半边的九宫，也就是 "
                        + palaceText(side), g));
            } else {
                out.add(Problem.at(sideName(side) + "的" + name + "不在九宫里（现在在 " + at
                        + "）：" + name + "只能待在 " + palaceText(side), g));
            }
        }
    }

    /** 某一方九宫的范围说明，行号按用户看到的 1~10 数 */
    public static String palaceText(Side side) {
        return side == Side.RED ? "第 8~10 行第 4~6 列" : "第 1~3 行第 4~6 列";
    }

    /**
     * 仕 / 士只能在九宫的两条斜线上 —— 九宫一共 9 个点，但仕一步斜走，
     * 能落脚只有 5 个（四个角加正中）。
     */
    private static void checkAdvisors(Board board, List<Problem> out) {
        for (Side side : Side.values()) {
            List<Pos> allowed = side == Side.RED ? RED_ADVISOR_POINTS : BLACK_ADVISOR_POINTS;
            String name = nameOf(PieceType.ADVISOR, side);
            for (Pos p : positionsOf(board, side, PieceType.ADVISOR)) {
                if (!board.inPalace(side, p.r, p.c)) {
                    out.add(Problem.at(sideName(side) + "的" + name + "只能待在九宫里", p));
                } else if (!contains(allowed, p.r, p.c)) {
                    out.add(Problem.at("第 " + (p.r + 1) + " 行第 " + (p.c + 1) + " 列的"
                            + name + "摆不了：仕走斜线，只能落在九宫的 "
                            + (side == Side.RED ? "第 10、8 行第 4、6 列和第 9 行第 5 列"
                                                : "第 1、3 行第 4、6 列和第 2 行第 5 列")
                            + " 这五个点上", p));
                }
            }
        }
    }

    /**
     * 相 / 象不能过河，而且只能在各自那 7 个"田字点"上 ——
     * 相走田，起点与落点固定，走遍全盘也只到得了这 7 个位置。
     */
    private static void checkElephants(Board board, List<Problem> out) {
        for (Side side : Side.values()) {
            List<Pos> allowed = side == Side.RED ? RED_ELEPHANT_POINTS : BLACK_ELEPHANT_POINTS;
            String name = nameOf(PieceType.ELEPHANT, side);
            for (Pos p : positionsOf(board, side, PieceType.ELEPHANT)) {
                boolean crossed = side == Side.RED ? p.r < 5 : p.r > 4;
                if (crossed) {
                    out.add(Problem.at(sideName(side) + "的" + name + "不能过河（只能待在第 "
                            + (side == Side.RED ? "6~10" : "1~5") + " 行）", p));
                } else if (!contains(allowed, p.r, p.c)) {
                    out.add(Problem.at("第 " + (p.r + 1) + " 行第 " + (p.c + 1) + " 列的"
                            + name + "摆不了：相走田，只能落在己方半场的 "
                            + (side == Side.RED ? "第 10、6 行第 3、7 列和第 8 行第 1、5、9 列"
                                                : "第 1、5 行第 3、7 列和第 3 行第 1、5、9 列")
                            + " 这七个点上", p));
                }
            }
        }
    }

    /**
     * 兵卒只能向前，不能退回自己后方。
     * <p>
     * 这里刻意把每一枚越界的兵卒都列出来，而不是发现一个就收工：落子前的预判要靠
     * 「问题里有没有出现刚放下的那一格」来判断，只报第一个的话，盘上先有一个摆错的兵时，
     * 再往错处放第二个就漏过去了。
     */
    private static void checkPawnsNotRetreated(Board board, List<Problem> out) {
        for (Pos p : positionsOf(board, Side.RED, PieceType.PAWN)) {
            if (p.r > 6) {
                out.add(Problem.at("第 " + (p.r + 1) + " 行第 " + (p.c + 1) + " 列的红兵摆不了："
                        + "红兵从第 7 行出发只能向前（向上）走，不能后退", p));
            }
        }
        for (Pos p : positionsOf(board, Side.BLACK, PieceType.PAWN)) {
            if (p.r < 3) {
                out.add(Problem.at("第 " + (p.r + 1) + " 行第 " + (p.c + 1) + " 列的黑卒摆不了："
                        + "黑卒从第 4 行出发只能向前（向下）走，不能后退", p));
            }
        }
    }

    private static void checkGeneralsNotFacing(Board board, List<Problem> out) {
        if (!board.generalsFacing()) return;
        List<Pos> kings = new ArrayList<>();
        for (Side side : Side.values()) {
            List<Pos> g = positionsOf(board, side, PieceType.GENERAL);
            if (!g.isEmpty()) kings.add(g.get(0));
        }
        out.add(new Problem("红黑两将照面了，中间得有棋子挡着", List.copyOf(kings)));
    }

    /**
     * 轮到走棋的一方，其对手不能正处于被将军状态 —— 否则说明对手上一手留下了自己被将的棋，
     * 这种局面在真实对局里不可能出现。
     */
    private static void checkOpponentNotInCheck(Board board, List<Problem> out) {
        Side toMove = board.getTurn();
        Side opponent = toMove.opponent();
        if (board.inCheck(opponent)) {
            out.add(Problem.at("轮到" + sideName(toMove) + "走，但" + sideName(opponent)
                    + "正被将军，这个局面接不上（这种情况一般应该轮到" + sideName(opponent) + "走）",
                    positionsOf(board, opponent, PieceType.GENERAL).toArray(new Pos[0])));
        }
    }

    /** 轮到的一方至少得有一手棋可走，否则一开局就已经被将死/困毙了 */
    private static void checkSideToMoveCanMove(Board board, List<Problem> out) {
        if (!board.hasLegalMove(board.getTurn())) {
            out.add(Problem.at("轮到" + sideName(board.getTurn()) + "走，但"
                    + sideName(board.getTurn()) + "已经无棋可走（已被将死或困毙），没法接着下",
                    positionsOf(board, board.getTurn(), PieceType.GENERAL).toArray(new Pos[0])));
        }
    }

    // ==================== 仕相的合法点位 ====================

    /** 红仕能落脚的五点（九宫的四个角加正中） */
    private static final List<Pos> RED_ADVISOR_POINTS = List.of(
            new Pos(9, 3), new Pos(9, 5), new Pos(8, 4), new Pos(7, 3), new Pos(7, 5));

    private static final List<Pos> BLACK_ADVISOR_POINTS = List.of(
            new Pos(0, 3), new Pos(0, 5), new Pos(1, 4), new Pos(2, 3), new Pos(2, 5));

    /** 红相能落脚的七个田字点 */
    private static final List<Pos> RED_ELEPHANT_POINTS = List.of(
            new Pos(9, 2), new Pos(9, 6), new Pos(7, 0), new Pos(7, 4), new Pos(7, 8),
            new Pos(5, 2), new Pos(5, 6));

    private static final List<Pos> BLACK_ELEPHANT_POINTS = List.of(
            new Pos(0, 2), new Pos(0, 6), new Pos(2, 0), new Pos(2, 4), new Pos(2, 8),
            new Pos(4, 2), new Pos(4, 6));

    private static boolean contains(List<Pos> points, int r, int c) {
        for (Pos p : points) {
            if (p.r == r && p.c == c) return true;
        }
        return false;
    }

    // ==================== 工具 ====================

    /** 盘面上某一方某一兵种的所有位置 */
    public static List<Pos> positionsOf(Board board, Side side, PieceType type) {
        List<Pos> out = new ArrayList<>();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Piece p = board.at(r, c);
                if (p != null && p.side == side && p.type == type) out.add(new Pos(r, c));
            }
        }
        return out;
    }

    /** 兵种的中文名（按方区分红黑用字），用于提示文案 */
    public static String nameOf(PieceType type, Side side) {
        boolean red = side == Side.RED;
        switch (type) {
            case GENERAL: return red ? "帅" : "将";
            case ADVISOR: return red ? "仕" : "士";
            case ELEPHANT: return red ? "相" : "象";
            case HORSE: return "马";
            case ROOK: return "车";
            case CANNON: return "炮";
            case PAWN: return red ? "兵" : "卒";
            default: return "棋子";
        }
    }

    private static String sideName(Side side) {
        return side == Side.RED ? "红方" : "黑方";
    }
}
