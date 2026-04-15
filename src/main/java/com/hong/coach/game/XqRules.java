package com.hong.coach.game;

import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 象棋走子基本规则
 */
public class XqRules {



    /**
     * 棋子枚举
     */
    public enum PieceType {
        GENERAL, ADVISOR, ELEPHANT, HORSE, ROOK, CANNON, PAWN
    }

    /**
     * 坐标
     */
    public static final class Pos {
        public final int r, c;

        public Pos(int r, int c) {
            this.r = r;
            this.c = c;
        }

        @Override
        public boolean equals(Object o) {
            if(!(o instanceof Pos)) return false;
            Pos p = (Pos) o;
            return p.r == r && p.c == c;
        }

        @Override
        public int hashCode() {
            return Objects.hash(r, c);
        }

        @Override
        public String toString() {
            return "(" + r + "," + c + ")";
        }
    }



    /* 棋子类 */

    public static abstract class Piece {
        public final PieceType type;
        public final Side side;
        public Pos pos;

        protected Piece(PieceType type, Side side, Pos pos) {
            this.type = type;
            this.side = side;
            this.pos = pos;
        }

        /**
         * Generate only "rule-based" moves (without considering self-check, face-to-face). Board will filter for legality.
         */
        public abstract List<Pos> pseudoLegalMoves(Board b);

        /**
         * Utility: direction increment arrays
         */
        protected static final int[][] ORTHO = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        protected static final int[][] DIAG1 = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

        /**
         * Utility: check if own piece
         */
        protected boolean isOwn(Board b, int r, int c) {
            Piece q = b.at(r, c);
            return q != null && q.side == this.side;
        }

        /**
         * Utility: check if enemy piece
         */
        protected boolean isEnemy(Board b, int r, int c) {
            Piece q = b.at(r, c);
            return q != null && q.side != this.side;
        }
    }

    /* ===================== Piece Rules ===================== */

    /**
     * Rook: 车
     */
    public static final class Rook extends Piece {
        public Rook(Side s, Pos p) {
            super(PieceType.ROOK, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                while (b.in(r, c)) {
                    if (b.at(r, c) == null) out.add(new Pos(r, c));
                    else {
                        if (isEnemy(b, r, c)) out.add(new Pos(r, c));
                        break;
                    }
                    r += d[0];
                    c += d[1];
                }
            }
            return out;
        }
    }

    /**
     * Cannon: 炮
     */
    public static final class Cannon extends Piece {
        public Cannon(Side s, Pos p) {
            super(PieceType.CANNON, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                boolean jumped = false;
                while (b.in(r, c)) {
                    Piece q = b.at(r, c);
                    if (!jumped) {
                        if (q == null) out.add(new Pos(r, c)); // Normal move
                        else jumped = true;                    // Cannon platform
                    } else {
                        if (q != null) {                       // Can capture first piece after platform
                            if (q.side != this.side) out.add(new Pos(r, c));
                            break;
                        }
                    }
                    r += d[0];
                    c += d[1];
                }
            }
            return out;
        }
    }

    /**
     * Horse: 马
     */
    public static final class Horse extends Piece {
        public Horse(Side s, Pos p) {
            super(PieceType.HORSE, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            int[][] legs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            int[][][] dsts = {
                    {{-2, -1}, {-2, 1}}, {{2, -1}, {2, 1}}, {{-1, -2}, {1, -2}}, {{-1, 2}, {1, 2}}
            };
            for (int i = 0; i < 4; i++) {
                int lr = pos.r + legs[i][0], lc = pos.c + legs[i][1]; // Horse leg
                if (!b.in(lr, lc) || b.at(lr, lc) != null) continue;      // Blocked horse leg
                for (int[] d : dsts[i]) {
                    int r = pos.r + d[0], c = pos.c + d[1];
                    if (!b.in(r, c) || isOwn(b, r, c)) continue;
                    out.add(new Pos(r, c));
                }
            }
            return out;
        }
    }

    /**
     * Elephant: 象
     */
    public static final class Elephant extends Piece {
        public Elephant(Side s, Pos p) {
            super(PieceType.ELEPHANT, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : DIAG1) {
                int r = pos.r + 2 * d[0], c = pos.c + 2 * d[1];
                int mr = pos.r + d[0], mc = pos.c + d[1]; // Elephant eye
                if (!b.in(r, c) || b.at(mr, mc) != null || isOwn(b, r, c)) continue;
                // Cannot cross river
                if (side == Side.RED && r <= 4) continue;
                if (side == Side.BLACK && r >= 5) continue;
                out.add(new Pos(r, c));
            }
            return out;
        }
    }

    /**
     * Advisor: 士
     */
    public static final class Advisor extends Piece {
        public Advisor(Side s, Pos p) {
            super(PieceType.ADVISOR, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : DIAG1) {
                int r = pos.r + d[0], c = pos.c + d[1];
                if (!b.in(r, c) || isOwn(b, r, c)) continue;
                if (b.inPalace(side, r, c)) out.add(new Pos(r, c));
            }
            return out;
        }
    }

    /**
     * General: 将/帅
     */
    public static final class General extends Piece {
        public General(Side s, Pos p) {
            super(PieceType.GENERAL, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            for (int[] d : ORTHO) {
                int r = pos.r + d[0], c = pos.c + d[1];
                if (!b.in(r, c) || isOwn(b, r, c)) continue;
                if (b.inPalace(side, r, c)) out.add(new Pos(r, c));
            }
            // Face-to-face capture (same column vertically with no pieces between)
            Pos opp = b.findGeneral(side.opponent());
            if (opp != null && opp.c == pos.c) {
                if (b.clearBetweenSameCol(pos.r, opp.r, pos.c)) {
                    out.add(new Pos(opp.r, opp.c));
                }
            }
            return out;
        }
    }

    /**
     * Pawn: 兵/卒
     */
    public static final class Pawn extends Piece {
        public Pawn(Side s, Pos p) {
            super(PieceType.PAWN, s, p);
        }

        @Override
        public List<Pos> pseudoLegalMoves(Board b) {
            List<Pos> out = new ArrayList<>();
            int fr = pos.r + side.dir, fc = pos.c; // Forward one step
            if (b.in(fr, fc) && !isOwn(b, fr, fc)) out.add(new Pos(fr, fc));
            // Can move sideways after crossing river
            boolean crossed = (side == Side.RED ? pos.r <= 4 : pos.r >= 5);
            if (crossed) {
                int[][] lr = {{0, -1}, {0, 1}};
                for (int[] d : lr) {
                    int r = pos.r, c = pos.c + d[1];
                    if (b.in(r, c) && !isOwn(b, r, c)) out.add(new Pos(r, c));
                }
            }
            return out;
        }
    }


}