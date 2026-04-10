package com.hong.coach.game;

/**
 * 红方/黑方
 */
public enum Side {
    RED(-1),   // Red at bottom, moves upward
    BLACK(+1); // Black at top, moves downward
    public final int dir;

    Side(int d) {
        this.dir = d;
    }

    public Side opponent() {
        return this == RED ? BLACK : RED;
    }
}
