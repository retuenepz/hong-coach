/**
 * 中国象棋盘面渲染：矢量绘制 + 走子动画 + 打击感特效。
 *
 * <p><b>为什么不用图片</b>：原先棋盘和棋子都是 GIF 贴图，每帧就是一句
 * {@code drawImage} 把图换个位置贴上去 —— 上一帧在起点、下一帧在终点，
 * 中间没有任何过渡，所以走棋看起来"啪"地一下就没了，毫无手感；
 * 样式也被死图锁死，想改个颜色都得重画一张图。
 *
 * <p>改成程序绘制之后，位置、缩放、旋转、透明度都能逐帧插值，
 * 动画 / 拖尾 / 轨迹 / 吃子迸溅 / 落子涟漪这些效果才成为可能。
 *
 * <p>对局页和摆棋页共用这一份，保证棋子在两个页面长得一模一样。
 * 对外只暴露全局 {@code XQ}。
 */
(function (global) {
    'use strict';

    /* ==================== 几何：棋盘落在画布上的位置 ==================== */

    const COLS = 9, ROWS = 10;
    const W = 450, H = 500;

    /**
     * 四周留白。棋盘是 8 格宽、9 格高（9 条竖线 10 条横线），
     * 之前按「9 格、10 格」去摊格距，多算的那一格全变成了右边和下边的空白。
     * 留白取 30：不小于边线上棋子光环的半径，不然贴边的棋子会被画布切掉。
     */
    const MARGIN = 30;
    /** 格距取正方形：横竖都塞得下，谁更挤按谁算 */
    const CELL = Math.min((W - MARGIN * 2) / (COLS - 1), (H - MARGIN * 2) / (ROWS - 1));
    const CELL_W = CELL, CELL_H = CELL;
    /** 交叉点原点：左右、上下留白对称，棋盘居中 */
    const PAD_L = (W - CELL * (COLS - 1)) / 2, PAD_T = (H - CELL * (ROWS - 1)) / 2;

    /** 棋子半径：略小于半格，留出一点"落在交叉点上"的呼吸感 */
    const R_PIECE = CELL * 0.43;

    /** 棋盘最外那条描边线离格线多远 */
    const FRAME = 9;

    /** 楷体优先，棋子上的字才有木刻的味道 */
    const FONT = '"KaiTi","STKaiti","Kaiti SC","SimSun",serif';

    /** 红黑两方的用字沿袭传统写法（红用帅仕相，黑用將士象） */
    const GLYPH = {
        R: '车', N: '马', B: '相', A: '仕', K: '帅', C: '炮', P: '兵',
        r: '車', n: '馬', b: '象', a: '士', k: '將', c: '砲', p: '卒'
    };

    const COLOR = {
        woodTop: '#F9EDD3', woodMid: '#F2DEB8', woodBottom: '#EAD3A4',
        frame: '#C7A06A', grid: '#9C7C52', river: '#BC9862',
        red: '#B23A38', redDeep: '#8E2B2A', redInk: '#A32D2D',
        black: '#33322E', blackDeep: '#1E1E1B', blackInk: '#2A2A27',
        blue: '#185FA5', blueSoft: 'rgba(24,95,165,', amber: 'rgba(186,117,23,'
    };

    function isRed(code) { return code === code.toUpperCase(); }
    function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }
    function easeOutCubic(t) { return 1 - Math.pow(1 - t, 3); }
    /** 起步慢、中段快、收尾稳 —— 走子用它最像"拿起来放下去" */
    function easeInOutCubic(t) { return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2; }
    function easeOutBack(t) { const c1 = 1.28, c3 = c1 + 1; return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2); }

    /** 服务端坐标 -> 屏幕格坐标（翻转时整体转 180°） */
    function toDisplay(r, c, flipped) {
        return flipped ? { r: ROWS - 1 - r, c: COLS - 1 - c } : { r: r, c: c };
    }
    function toBoard(dr, dc, flipped) {
        return flipped ? { r: ROWS - 1 - dr, c: COLS - 1 - dc } : { r: dr, c: dc };
    }
    /**
     * 某个交叉点的画布坐标。象棋的子落在<b>线与线的交叉点</b>上，
     * 不是格子的正中间 —— 这里若偏半格，整盘棋子看着就是歪的。
     */
    function centerOf(r, c, flipped) {
        const d = toDisplay(r, c, flipped);
        return { x: PAD_L + d.c * CELL_W, y: PAD_T + d.r * CELL_H };
    }

    /** 以交叉点为中心的一格矩形，用来画高亮底色 */
    function rectOf(r, c, flipped) {
        const d = toDisplay(r, c, flipped);
        return {
            x: PAD_L + d.c * CELL_W - CELL_W / 2,
            y: PAD_T + d.r * CELL_H - CELL_H / 2,
            w: CELL_W, h: CELL_H
        };
    }

    /** 鼠标坐标 -> 最近的交叉点；离棋盘太远返回 null */
    function hitTest(clientX, clientY, canvas, flipped) {
        const box = canvas.getBoundingClientRect();
        const gx = clientX - box.left - PAD_L;
        const gy = clientY - box.top - PAD_T;
        // 容差留半格：点在交叉点附近的空白处也应命中
        if (gx < -CELL_W * 0.45 || gy < -CELL_H * 0.45
            || gx > CELL_W * (COLS - 1) + CELL_W * 0.45
            || gy > CELL_H * (ROWS - 1) + CELL_H * 0.45) return null;
        const dc = Math.round(gx / CELL_W), dr = Math.round(gy / CELL_H);
        if (dc < 0 || dc >= COLS || dr < 0 || dr >= ROWS) return null;
        return toBoard(dr, dc, flipped);
    }

    /* ==================== 棋盘：画一次缓存起来 ==================== */

    /**
     * 棋盘本身是 180° 旋转对称的（左右、上下都对称，九宫和兵炮位也对称），
     * 所以翻转视角不需要重画，一张缓存图两边通用。
     */
    let boardCache = null;

    function boardLayer() {
        if (boardCache) return boardCache;
        const dpr = global.devicePixelRatio || 1;
        const cv = document.createElement('canvas');
        cv.width = Math.round(W * dpr);
        cv.height = Math.round(H * dpr);
        const g = cv.getContext('2d');
        g.scale(dpr, dpr);
        paintBoard(g);
        boardCache = cv;
        return cv;
    }

    function paintBoardLayer(ctx) {
        ctx.drawImage(boardLayer(), 0, 0, W, H);
    }

    function paintBoard(g) {
        const x = c => PAD_L + c * CELL_W;
        const y = r => PAD_T + r * CELL_H;

        // --- 底色：温润的纸木色，纵向带一点明暗过渡，别做成纯色 ---
        const bg = g.createLinearGradient(0, 0, 0, H);
        bg.addColorStop(0, COLOR.woodTop);
        bg.addColorStop(0.5, COLOR.woodMid);
        bg.addColorStop(1, COLOR.woodBottom);
        g.fillStyle = bg;
        g.fillRect(0, 0, W, H);

        // --- 极淡的木纹，纯装饰 ---
        g.save();
        g.globalAlpha = 0.05;
        g.strokeStyle = '#8B5E2A';
        g.lineWidth = 1;
        for (let i = 0; i < 26; i++) {
            const yy = (i * 19.3) % H;
            g.beginPath();
            g.moveTo(0, yy);
            g.bezierCurveTo(W * 0.3, yy + 3, W * 0.7, yy - 3, W, yy + 1);
            g.stroke();
        }
        g.restore();

        // --- 外框：套在最外一圈线外面（8 格宽、9 格高，不是 9/10） ---
        g.strokeStyle = COLOR.frame;
        g.lineWidth = 1.4;
        g.strokeRect(x(0) - FRAME, y(0) - FRAME,
            CELL_W * (COLS - 1) + FRAME * 2, CELL_H * (ROWS - 1) + FRAME * 2);

        // --- 网格 ---
        g.strokeStyle = COLOR.grid;
        g.lineWidth = 1;
        g.beginPath();
        for (let r = 0; r < ROWS; r++) {                 // 10 条横线，贯通
            g.moveTo(x(0), y(r));
            g.lineTo(x(COLS - 1), y(r));
        }
        g.moveTo(x(0), y(0)); g.lineTo(x(0), y(ROWS - 1));      // 左右两条边线贯通
        g.moveTo(x(COLS - 1), y(0)); g.lineTo(x(COLS - 1), y(ROWS - 1));
        for (let c = 1; c < COLS - 1; c++) {             // 中间七条被楚河汉界断开
            g.moveTo(x(c), y(0)); g.lineTo(x(c), y(4));
            g.moveTo(x(c), y(5)); g.lineTo(x(c), y(ROWS - 1));
        }
        g.stroke();

        // --- 九宫斜线 ---
        g.beginPath();
        g.moveTo(x(3), y(0)); g.lineTo(x(5), y(2));
        g.moveTo(x(5), y(0)); g.lineTo(x(3), y(2));
        g.moveTo(x(3), y(7)); g.lineTo(x(5), y(9));
        g.moveTo(x(5), y(7)); g.lineTo(x(3), y(9));
        g.stroke();

        // --- 兵位 / 炮位的小角标 ---
        [3, 6].forEach(r => [0, 2, 4, 6, 8].forEach(c => positionMark(g, r, c)));
        [2, 7].forEach(r => [1, 7].forEach(c => positionMark(g, r, c)));

        // --- 楚河 汉界（不跟着翻转走，永远正着写，两边都读得顺） ---
        g.save();
        g.fillStyle = COLOR.river;
        g.font = '500 ' + Math.round(CELL_H * 0.52) + 'px ' + FONT;
        g.textAlign = 'center';
        g.textBaseline = 'middle';
        const my = (y(4) + y(5)) / 2;
        g.fillText('楚  河', x(2), my);
        g.fillText('漢  界', x(6), my);
        g.restore();
    }

    /** 兵、炮位上的传统角标：四个斜角各画一个小折角 */
    function positionMark(g, r, c) {
        const p = centerOf(r, c, false);
        const gap = 3.6, len = CELL_W * 0.17;
        const dirs = [[-1, -1], [1, -1], [-1, 1], [1, 1]];
        g.save();
        g.strokeStyle = COLOR.grid;
        g.lineWidth = 1;
        g.beginPath();
        dirs.forEach(function (d) {
            const sx = d[0], sy = d[1];
            if (c === 0 && sx < 0) return;            // 棋盘外的那两个角不画
            if (c === COLS - 1 && sx > 0) return;
            const px = p.x + sx * gap, py = p.y + sy * gap;
            g.moveTo(px, py + sy * len);
            g.lineTo(px, py);
            g.lineTo(px + sx * len, py);
        });
        g.stroke();
        g.restore();
    }

    /* ==================== 棋子：程序绘制 + 位图缓存 ==================== */

    /**
     * 棋子每帧都要画（32 枚 × 60fps），逐帧渲染渐变和文字太浪费，
     * 所以按「棋子 + 半径 + 像素比」缓存成小位图，之后只做 drawImage。
     */
    const spriteCache = new Map();

    function pieceSprite(code, radius) {
        const dpr = global.devicePixelRatio || 1;
        const key = code + '|' + radius.toFixed(2) + '|' + dpr;
        const hit = spriteCache.get(key);
        if (hit) return hit;

        const spill = Math.max(3, radius * 0.18);      // 给投影和描边留的余量
        const size = Math.ceil((radius + spill) * 2);
        const cv = document.createElement('canvas');
        cv.width = Math.round(size * dpr);
        cv.height = Math.round(size * dpr);
        const g = cv.getContext('2d');
        g.scale(dpr, dpr);
        paintPieceFace(g, size / 2, size / 2, radius, code);

        const sprite = { canvas: cv, size: size };
        spriteCache.set(key, sprite);
        return sprite;
    }

    /** 一枚棋子的正面：投影 + 象牙底 + 侧别色圈 + 楷体字 */
    function paintPieceFace(g, cx, cy, r, code) {
        const red = isRed(code);
        const rim = red ? COLOR.red : COLOR.black;
        const rimDeep = red ? COLOR.redDeep : COLOR.blackDeep;
        const ink = red ? COLOR.redInk : COLOR.blackInk;

        g.save();

        // 落地投影：让棋子看着是"搁"在棋盘上，而不是浮着
        const sh = g.createRadialGradient(cx + r * 0.06, cy + r * 0.16, r * 0.2, cx + r * 0.06, cy + r * 0.16, r * 1.22);
        sh.addColorStop(0, 'rgba(90,62,28,.30)');
        sh.addColorStop(0.72, 'rgba(90,62,28,.13)');
        sh.addColorStop(1, 'rgba(90,62,28,0)');
        g.beginPath();
        g.arc(cx + r * 0.06, cy + r * 0.16, r * 1.22, 0, Math.PI * 2);
        g.fillStyle = sh;
        g.fill();

        // 棋子本体：骨质的暖白，左上打光
        const face = g.createRadialGradient(cx - r * 0.34, cy - r * 0.4, r * 0.12, cx, cy, r * 1.06);
        face.addColorStop(0, '#FFFEF8');
        face.addColorStop(0.5, '#FAF1DC');
        face.addColorStop(0.86, '#EFE0BF');
        face.addColorStop(1, '#E2CDA4');
        g.beginPath();
        g.arc(cx, cy, r, 0, Math.PI * 2);
        g.fillStyle = face;
        g.fill();

        // 外圈：红黑两方的身份靠它区分
        g.lineWidth = Math.max(1.6, r * 0.12);
        g.strokeStyle = rim;
        g.stroke();

        // 内细圈，老棋子上刻的那一道
        g.beginPath();
        g.arc(cx, cy, r * 0.755, 0, Math.PI * 2);
        g.lineWidth = Math.max(0.8, r * 0.045);
        g.strokeStyle = red ? 'rgba(178,58,56,.5)' : 'rgba(51,50,46,.42)';
        g.stroke();

        // 顶部弧形高光
        g.beginPath();
        g.arc(cx, cy, r * 0.86, Math.PI * 1.16, Math.PI * 1.62);
        g.lineWidth = Math.max(1, r * 0.1);
        g.strokeStyle = 'rgba(255,255,255,.6)';
        g.stroke();

        // 字：用侧别色，压一层淡淡的字影，看着是刻进去的
        const fs = Math.round(r * 1.02);
        g.font = '600 ' + fs + 'px ' + FONT;
        g.textAlign = 'center';
        g.textBaseline = 'middle';
        g.fillStyle = 'rgba(255,255,255,.75)';
        g.fillText(GLYPH[code] || '?', cx, cy + r * 0.07 + 1);
        g.fillStyle = ink;
        g.fillText(GLYPH[code] || '?', cx, cy + r * 0.07);

        // 内圈阴影收一下边，增加一点厚度
        g.beginPath();
        g.arc(cx, cy, r - g.lineWidth * 0.5, Math.PI * 0.15, Math.PI * 0.85);
        g.lineWidth = Math.max(1, r * 0.09);
        g.strokeStyle = 'rgba(150,120,70,.16)';
        g.stroke();

        g.restore();
    }

    /** 把一枚棋子画到 (x, y)，scale / rotate / alpha 都留给动画插值用 */
    function drawPiece(ctx, x, y, radius, code, opts) {
        if (!code) return;
        opts = opts || {};
        const sp = pieceSprite(code, radius);
        const scale = (opts.scale == null ? 1 : opts.scale);
        const half = sp.size / 2;
        ctx.save();
        ctx.globalAlpha = clamp(opts.alpha == null ? 1 : opts.alpha, 0, 1);
        ctx.translate(x, y);
        if (scale !== 1) ctx.scale(scale, scale);
        if (opts.rotate) ctx.rotate(opts.rotate);
        ctx.drawImage(sp.canvas, -half, -half, sp.size, sp.size);
        ctx.restore();
    }

    /* ==================== 高亮与提示 ==================== */

    /** 外框矩形：棋盘最外一圈线再往外 FRAME px，高亮底色不许超过它 */
    function frameBounds() {
        return {
            x: PAD_L - FRAME, y: PAD_T - FRAME,
            w: CELL_W * (COLS - 1) + FRAME * 2, h: CELL_H * (ROWS - 1) + FRAME * 2
        };
    }

    /** 格子底色：上一手走过的两格 */
    function paintCellTint(ctx, r, c, flipped, color, radiusRatio) {
        const q = rectOf(r, c, flipped);
        // 贴边的那一格只画半格会难看，索性整体裁进外框内
        const f = frameBounds();
        const rad = (radiusRatio == null ? 0.18 : radiusRatio) * Math.min(q.w, q.h);
        const x1 = Math.max(q.x, f.x), y1 = Math.max(q.y, f.y);
        const x2 = Math.min(q.x + q.w, f.x + f.w), y2 = Math.min(q.y + q.h, f.y + f.h);
        if (x2 <= x1 || y2 <= y1) return;
        roundRect(ctx, x1 + 1, y1 + 1, x2 - x1 - 2, y2 - y1 - 2, rad);
        ctx.fillStyle = color;
        ctx.fill();
    }

    /** 选中棋子时，脚下的一圈脉动光环 */
    function paintSelection(ctx, r, c, flipped, now) {
        const p = centerOf(r, c, flipped);
        const pulse = 0.5 + 0.5 * Math.sin(now / 260);
        ctx.save();
        ctx.beginPath();
        ctx.arc(p.x, p.y, R_PIECE * (1.24 + pulse * 0.1), 0, Math.PI * 2);
        ctx.strokeStyle = 'rgba(24,95,165,' + (0.45 + pulse * 0.35).toFixed(3) + ')';
        ctx.lineWidth = 2.6;
        ctx.stroke();
        ctx.beginPath();
        ctx.arc(p.x, p.y, R_PIECE * 1.12, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(24,95,165,.10)';
        ctx.fill();
        ctx.restore();
    }

    /** 被将军的老将：一圈急促的红光 */
    function paintCheckPulse(ctx, r, c, flipped, now) {
        const p = centerOf(r, c, flipped);
        const pulse = 0.5 + 0.5 * Math.sin(now / 150);
        ctx.save();
        ctx.beginPath();
        ctx.arc(p.x, p.y, R_PIECE * (1.2 + pulse * 0.42), 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(200,40,40,' + (0.24 - pulse * 0.12).toFixed(3) + ')';
        ctx.fill();
        ctx.strokeStyle = 'rgba(200,40,40,' + (0.7 - pulse * 0.25).toFixed(3) + ')';
        ctx.lineWidth = 2.4;
        ctx.stroke();
        ctx.restore();
    }

    /** 可落点：空位画蓝点，有子可吃画红圈 */
    function paintMoveTargets(ctx, board, flipped, targets) {
        if (!targets || !targets.length) return;
        targets.forEach(function (t) {
            const p = centerOf(t.r, t.c, flipped);
            const occupied = board && board[t.r] && board[t.r][t.c];
            ctx.save();
            if (occupied) {
                ctx.beginPath();
                ctx.arc(p.x, p.y, R_PIECE * 1.08, 0, Math.PI * 2);
                ctx.strokeStyle = 'rgba(178,58,56,.85)';
                ctx.lineWidth = 2.6;
                ctx.stroke();
                ctx.setLineDash([4, 4]);
                ctx.beginPath();
                ctx.arc(p.x, p.y, R_PIECE * 1.32, 0, Math.PI * 2);
                ctx.strokeStyle = 'rgba(178,58,56,.4)';
                ctx.lineWidth = 1.6;
                ctx.stroke();
                ctx.setLineDash([]);
            } else {
                ctx.beginPath();
                ctx.arc(p.x, p.y, 7, 0, Math.PI * 2);
                ctx.fillStyle = 'rgba(24,95,165,.55)';
                ctx.fill();
                ctx.beginPath();
                ctx.arc(p.x, p.y, 11, 0, Math.PI * 2);
                ctx.strokeStyle = 'rgba(24,95,165,.28)';
                ctx.lineWidth = 1.6;
                ctx.stroke();
            }
            ctx.restore();
        });
    }

    /**
     * 摆棋校验出来的问题位置。服务端只回坐标，怎么画是这里的事 ——
     * 光给一列文字，用户得自己数到第几行第几列；圈出来就一眼知道该挪哪个子。
     */
    let problemCells = [];

    function setProblemCells(cells) {
        problemCells = Array.isArray(cells) ? cells.slice() : [];
    }

    /** 有问题的格子：脉动的红色虚线圈 + 一层淡红底 */
    function paintProblemMarks(ctx, flipped, nowTs) {
        if (!problemCells.length) return;
        const nowMs = nowTs == null ? now() : nowTs;
        for (let i = 0; i < problemCells.length; i++) {
            const cell = problemCells[i];
            const p = centerOf(cell.r, cell.c, flipped);
            const pulse = 0.5 + 0.5 * Math.sin(nowMs / 220 + i * 0.6);
            ctx.save();
            ctx.beginPath();
            ctx.arc(p.x, p.y, R_PIECE * 1.16, 0, Math.PI * 2);
            ctx.fillStyle = 'rgba(200,40,40,' + (0.10 + pulse * 0.07).toFixed(3) + ')';
            ctx.fill();
            ctx.beginPath();
            ctx.arc(p.x, p.y, R_PIECE * (1.04 + pulse * 0.16), 0, Math.PI * 2);
            ctx.setLineDash([5, 4]);
            ctx.lineDashOffset = -nowMs / 40;
            ctx.strokeStyle = 'rgba(200,40,40,' + (0.55 + pulse * 0.35).toFixed(3) + ')';
            ctx.lineWidth = 2.6;
            ctx.stroke();
            ctx.setLineDash([]);
            ctx.restore();
        }
    }

    function roundRect(ctx, x, y, w, h, r) {
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.arcTo(x + w, y, x + w, y + h, r);
        ctx.arcTo(x + w, y + h, x, y + h, r);
        ctx.arcTo(x, y + h, x, y, r);
        ctx.arcTo(x, y, x + w, y, r);
        ctx.closePath();
    }

    /* ==================== 落子前的预判提示 ==================== */

    /**
     * 手里拿着棋子时，鼠标底下那一格先给个预判：绿圈＝放得下，红圈＝放不下。
     * <p>
     * 摆棋的校验本来就该发生在落子<b>之前</b> —— 先说"这儿放不下"，用户就不会
     * 先把子摆上去、再从一串报错里回头找该挪哪个。圈的颜色就是这一次判断的结果。
     */
    let ghost = null;          // {r, c, ok} 鼠标底下的那一格
    const refusals = [];       // 被规则挡下来的落子，给一记短促的红叉

    /** 设置/清除鼠标底下那一格的预判标记 */
    function setGhost(cell, ok) {
        ghost = cell ? { r: cell.r, c: cell.c, ok: !!ok } : null;
    }

    /** 这一下落子被规则挡下了：盘面不动，只给一声"放不下去"的反馈 */
    function refuse(cell) {
        if (!cell) return;
        refusals.push({ r: cell.r, c: cell.c, t0: now() });
        fx.shake = { t0: now(), dur: 170, mag: 2.2 };
        sound('refuse');
    }

    /** 画预判圈与被拒的红叉，要压在棋子上层 */
    function paintHints(ctx, flipped, nowTs) {
        const nowMs = nowTs == null ? now() : nowTs;
        drawRefusals(ctx, flipped, nowMs);
        drawGhost(ctx, flipped, nowMs);
    }

    function drawGhost(ctx, flipped, nowMs) {
        if (!ghost) return;
        const p = centerOf(ghost.r, ghost.c, flipped);
        const rgb = ghost.ok ? '12,107,61' : '200,40,40';
        const pulse = 0.5 + 0.5 * Math.sin(nowMs / 200);
        ctx.save();
        ctx.beginPath();
        ctx.arc(p.x, p.y, R_PIECE * 1.16, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(' + rgb + ',' + (0.07 + pulse * 0.05).toFixed(3) + ')';
        ctx.fill();
        ctx.beginPath();
        ctx.arc(p.x, p.y, R_PIECE * 1.24, 0, Math.PI * 2);
        ctx.setLineDash([6, 5]);
        ctx.lineDashOffset = -nowMs / 36;
        ctx.strokeStyle = 'rgba(' + rgb + ',' + (0.5 + pulse * 0.35).toFixed(3) + ')';
        ctx.lineWidth = 2.4;
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.restore();
    }

    /** 被拒的落子：一圈迅速扩散的红环 + 一个"不行"的叉 */
    function drawRefusals(ctx, flipped, nowMs) {
        for (let i = refusals.length - 1; i >= 0; i--) {
            const rf = refusals[i];
            const t = (nowMs - rf.t0) / 480;
            if (t >= 1) { refusals.splice(i, 1); continue; }
            const p = centerOf(rf.r, rf.c, flipped);
            const e = easeOutCubic(t);
            const a = 1 - t;
            ctx.save();
            ctx.globalAlpha = a;
            ctx.beginPath();
            ctx.arc(p.x, p.y, R_PIECE * (1.0 + e * 0.95), 0, Math.PI * 2);
            ctx.strokeStyle = 'rgba(200,40,40,' + (0.85 * a).toFixed(3) + ')';
            ctx.lineWidth = 3.4 * a + 0.6;
            ctx.stroke();
            const k = R_PIECE * 0.5;
            ctx.lineCap = 'round';
            ctx.strokeStyle = 'rgba(200,40,40,' + (0.85 * a).toFixed(3) + ')';
            ctx.lineWidth = 3.2;
            ctx.beginPath();
            ctx.moveTo(p.x - k, p.y - k); ctx.lineTo(p.x + k, p.y + k);
            ctx.moveTo(p.x + k, p.y - k); ctx.lineTo(p.x - k, p.y + k);
            ctx.stroke();
            ctx.restore();
        }
    }

    /* ==================== 动画与特效 ==================== */

    const fx = {
        moves: [],      // 正在飞的棋子
        grazes: [],     // 被吃掉的棋子被打飞
        ripples: [],    // 落子涟漪
        sparks: [],     // 吃子碎屑
        trails: [],     // 走完留下的轨迹残影
        drops: [],      // 摆棋时棋子"落"到棋盘上
        shake: null
    };

    const MOVE_MIN = 240, MOVE_MAX = 430;

    /**
     * 播一次走子。调用方必须已经把棋子从 from 挪到了 to 的盘面上 ——
     * 这里只管"演",终点那一格在飞行期间会被跳过不画，落点由动画补上。
     *
     * @param from     {r,c} 起点
     * @param to       {r,c} 终点
     * @param code     走的这枚棋子
     * @param captured 被吃的棋子（没有就传 null）
     * @param flipped  当前视角
     * @param opts     {silent:true} 时不发声
     */
    function playMove(o) {
        const flipped = !!o.flipped;
        const a = centerOf(o.from.r, o.from.c, flipped);
        const b = centerOf(o.to.r, o.to.c, flipped);
        const dist = Math.hypot(b.x - a.x, b.y - a.y);
        // 走得远的子飞得久一点，手感上"路程"才有意义
        const dur = clamp(170 + dist * 2.7, MOVE_MIN, MOVE_MAX);
        const anim = {
            from: a, to: b, code: o.code, captured: o.captured || null,
            t0: now(), dur: dur, landed: false, toCell: o.to
        };
        fx.moves.push(anim);
        // 落子声跟着用户的操作走，是"手感"的一半 —— 响了这一下，动作才成立
        if (!o.silent) sound(o.captured ? 'capture' : 'move');
        return anim;
    }

    /** 棋子底下那层：轨迹残影 —— 不能盖住棋盘上的棋子 */
    function paintUnderPieces(ctx, nowTs) {
        const nowMs = nowTs == null ? now() : nowTs;
        drawTrails(ctx, nowMs);
    }

    /** 棋子上面那层：飞行中的棋子、落子涟漪、吃子迸溅 */
    function paintEffects(ctx, nowTs) {
        const nowMs = nowTs == null ? now() : nowTs;
        for (let i = fx.moves.length - 1; i >= 0; i--) {
            const m = fx.moves[i];
            const raw = (nowMs - m.t0) / m.dur;

            if (raw >= 1) {
                if (!m.landed) { onLand(m, nowMs); m.landed = true; }
                // 落子后还留一小段"压一下"的余韵
                if (nowMs - m.t0 > m.dur + 170) {
                    fx.moves.splice(i, 1);
                    continue;
                }
            }

            const t = clamp(raw, 0, 1);
            const e = easeInOutCubic(t);
            const x = m.from.x + (m.to.x - m.from.x) * e;
            const y = m.from.y + (m.to.y - m.from.y) * e;

            // 抬起再落下：中间微微拱起，落点更实
            const lift = Math.sin(Math.PI * t) * Math.min(9, 5 + m.dur * 0.012);
            const scale = 1 + Math.sin(Math.PI * t) * 0.14 - (raw > 1 ? 0.1 * (1 - clamp((nowMs - m.t0 - m.dur) / 170, 0, 1)) : 0);
            const tilt = Math.sin(Math.PI * t) * 0.07 * (m.to.x >= m.from.x ? 1 : -1);

            // 拖尾：把前面几帧的位置以递减透明度重画一遍，形成运动残影
            if (t < 1) {
                for (let k = 4; k >= 1; k--) {
                    const tk = t - k * 0.07;
                    if (tk <= 0) continue;
                    const ek = easeInOutCubic(tk);
                    drawPiece(ctx,
                        m.from.x + (m.to.x - m.from.x) * ek,
                        m.from.y + (m.to.y - m.from.y) * ek - Math.sin(Math.PI * tk) * lift,
                        R_PIECE, m.code,
                        { alpha: 0.3 * (1 - k / 5) + 0.06, scale: 1 - k * 0.03 });
                }
            }

            // 地面投影：飞得越高投影越淡越大，落下来就收实
            ctx.save();
            ctx.globalAlpha = 0.34 * (1 - Math.sin(Math.PI * t) * 0.55);
            ctx.beginPath();
            ctx.ellipse(x + 2, y + R_PIECE * 0.82, R_PIECE * (0.72 + t * 0.1), R_PIECE * 0.3, 0, 0, Math.PI * 2);
            ctx.fillStyle = 'rgba(70,48,20,.55)';
            ctx.fill();
            ctx.restore();

            drawPiece(ctx, x, y - lift, R_PIECE, m.code, { scale: scale, rotate: tilt });
        }

        drawDrops(ctx, nowMs);
        // 被吃掉的子被打飞到空中，要画在所有棋子上面才看得见
        drawGrazes(ctx, nowMs);
        drawRipples(ctx, nowMs);
        drawSparks(ctx, nowMs);
    }

    /**
     * 摆棋时把一枚子"放"到盘上：从略大缩到正常尺寸再回弹一下，配一圈涟漪。
     * 不做这个的话，点一下棋盘子就凭空出现，跟自己动手摆棋的感觉对不上。
     */
    function dropIn(o) {
        const p = centerOf(o.r, o.c, !!o.flipped);
        const t0 = now();
        fx.drops.push({ x: p.x, y: p.y, code: o.code, t0: t0, dur: 230, cell: { r: o.r, c: o.c } });
        fx.ripples.push({ x: p.x, y: p.y, t0: t0, dur: 340, capture: false });
    }

    function drawDrops(ctx, nowMs) {
        for (let i = fx.drops.length - 1; i >= 0; i--) {
            const d = fx.drops[i];
            const t = (nowMs - d.t0) / d.dur;
            if (t >= 1) { fx.drops.splice(i, 1); continue; }
            drawPiece(ctx, d.x, d.y, R_PIECE, d.code,
                { scale: 1 + (1 - easeOutBack(t)) * 0.55, alpha: Math.min(1, t * 4) });
        }
    }

    /** 落点瞬间：涟漪 + 震动 + 留一条轨迹 */
    function onLand(m, nowMs) {
        fx.ripples.push({ x: m.to.x, y: m.to.y, t0: nowMs, dur: 420, capture: !!m.captured });
        fx.shake = { t0: nowMs, dur: m.captured ? 220 : 140, mag: m.captured ? 3 : 1.3 };

        if (m.captured) {
            // 被吃的子沿走子方向被打飞，边飞边转边淡出
            const dx = m.to.x - m.from.x, dy = m.to.y - m.from.y;
            const len = Math.hypot(dx, dy) || 1;
            fx.grazes.push({
                x: m.to.x, y: m.to.y, code: m.captured, t0: nowMs, dur: 480,
                dx: dx / len, dy: dy / len
            });
            for (let i = 0; i < 12; i++) {
                const ang = (i / 12) * Math.PI * 2 + Math.random() * 0.5;
                const sp = 55 + Math.random() * 90;
                fx.sparks.push({
                    x: m.to.x, y: m.to.y, vx: Math.cos(ang) * sp, vy: Math.sin(ang) * sp - 40,
                    t0: nowMs, dur: 380 + Math.random() * 200,
                    color: isRed(m.captured) ? COLOR.redInk : COLOR.blackInk
                });
            }
        }

        // 轨迹残影：起点留一枚虚影，两点之间画一条带箭头的弧线，2.4 秒内淡掉
        fx.trails.push({
            from: m.from, to: m.to, code: m.code, t0: nowMs, life: 2400
        });
    }

    function drawTrails(ctx, nowMs) {
        for (let i = fx.trails.length - 1; i >= 0; i--) {
            const p = fx.trails[i];
            const k = (nowMs - p.t0) / p.life;
            if (k >= 1) { fx.trails.splice(i, 1); continue; }
            const a = 1 - k;

            const dx = p.to.x - p.from.x, dy = p.to.y - p.from.y;
            const len = Math.hypot(dx, dy) || 1;
            const nx = -dy / len, ny = dx / len;
            const bulge = len * 0.13;
            const cx = (p.from.x + p.to.x) / 2 + nx * bulge;
            const cy = (p.from.y + p.to.y) / 2 + ny * bulge;

            ctx.save();
            // 起点虚影
            drawPiece(ctx, p.from.x, p.from.y, R_PIECE, p.code, { alpha: a * 0.16, scale: 0.94 });

            // 弧线本身，虚线沿着轨迹跑一小段
            ctx.beginPath();
            ctx.moveTo(p.from.x, p.from.y);
            ctx.quadraticCurveTo(cx, cy, p.to.x, p.to.y);
            ctx.setLineDash([6, 6]);
            ctx.lineDashOffset = -k * 60;
            ctx.strokeStyle = 'rgba(24,95,165,' + (a * 0.42).toFixed(3) + ')';
            ctx.lineWidth = 2;
            ctx.stroke();
            ctx.setLineDash([]);

            // 箭头指向终点，表明"从哪儿来、往哪儿去"
            const tA = 0.82;
            const ax = quadAt(p.from.x, cx, p.to.x, tA), ay = quadAt(p.from.y, cy, p.to.y, tA);
            const tx = quadAt(p.from.x, cx, p.to.x, tA + 0.06) - ax;
            const ty = quadAt(p.from.y, cy, p.to.y, tA + 0.06) - ay;
            const tl = Math.hypot(tx, ty) || 1;
            const ux = tx / tl, uy = ty / tl;
            ctx.beginPath();
            ctx.moveTo(ax + ux * 7, ay + uy * 7);
            ctx.lineTo(ax - uy * 5, ay + ux * 5);
            ctx.lineTo(ax + uy * 5, ay - ux * 5);
            ctx.closePath();
            ctx.fillStyle = 'rgba(24,95,165,' + (a * 0.6).toFixed(3) + ')';
            ctx.fill();
            ctx.restore();
        }
    }

    function quadAt(p0, p1, p2, t) {
        const u = 1 - t;
        return u * u * p0 + 2 * u * t * p1 + t * t * p2;
    }

    function drawGrazes(ctx, nowMs) {
        for (let i = fx.grazes.length - 1; i >= 0; i--) {
            const gz = fx.grazes[i];
            const t = (nowMs - gz.t0) / gz.dur;
            if (t >= 1) { fx.grazes.splice(i, 1); continue; }
            const e = easeOutCubic(t);
            const d = 58 * e;
            drawPiece(ctx,
                gz.x + gz.dx * d,
                gz.y + gz.dy * d - 20 * Math.sin(Math.PI * t),
                R_PIECE, gz.code,
                { alpha: 1 - t * t, scale: 1 - 0.45 * t, rotate: gz.dx * 2.6 * t });
        }
    }

    function drawRipples(ctx, nowMs) {
        for (let i = fx.ripples.length - 1; i >= 0; i--) {
            const rp = fx.ripples[i];
            const t = (nowMs - rp.t0) / rp.dur;
            if (t >= 1) { fx.ripples.splice(i, 1); continue; }
            const e = easeOutCubic(t);
            ctx.save();
            // 一圈扩散的光环
            ctx.beginPath();
            ctx.arc(rp.x, rp.y, R_PIECE * (0.72 + e * 1.5), 0, Math.PI * 2);
            ctx.strokeStyle = (rp.capture ? 'rgba(178,58,56,' : 'rgba(24,95,165,') + ((1 - t) * 0.6).toFixed(3) + ')';
            ctx.lineWidth = 3 * (1 - t) + 0.6;
            ctx.stroke();
            // 落点一闪
            if (t < 0.35) {
                ctx.beginPath();
                ctx.arc(rp.x, rp.y, R_PIECE * (0.5 + e * 0.6), 0, Math.PI * 2);
                ctx.fillStyle = 'rgba(255,250,230,' + ((0.35 - t) * 0.8).toFixed(3) + ')';
                ctx.fill();
            }
            ctx.restore();
        }
    }

    function drawSparks(ctx, nowMs) {
        for (let i = fx.sparks.length - 1; i >= 0; i--) {
            const s = fx.sparks[i];
            const t = (nowMs - s.t0) / s.dur;
            if (t >= 1) { fx.sparks.splice(i, 1); continue; }
            const tt = (nowMs - s.t0) / 1000;
            const x = s.x + s.vx * tt;
            const y = s.y + s.vy * tt + 150 * tt * tt;
            ctx.save();
            ctx.globalAlpha = 1 - t;
            ctx.beginPath();
            ctx.arc(x, y, 2.2 * (1 - t) + 0.6, 0, Math.PI * 2);
            ctx.fillStyle = s.color;
            ctx.fill();
            ctx.restore();
        }
    }

    /** 棋盘被"震"一下的位移量，渲染时整体 translate 即可 */
    function shakeOffset(nowMs) {
        if (!fx.shake) return { x: 0, y: 0 };
        const s = fx.shake;
        const t = ((nowMs == null ? now() : nowMs) - s.t0) / s.dur;
        if (t >= 1) { fx.shake = null; return { x: 0, y: 0 }; }
        const decay = (1 - t) * s.mag;
        return { x: Math.sin(t * 46) * decay, y: Math.cos(t * 38) * decay * 0.7 };
    }

    /** 还在飞的格子：这一格先别画静态棋子，交给动画收尾 */
    function flyingCells() {
        const set = new Set();
        fx.moves.forEach(function (m) { set.add(m.toCell.r + ',' + m.toCell.c); });
        fx.drops.forEach(function (d) { set.add(d.cell.r + ',' + d.cell.c); });
        return set;
    }

    /** 还有没有特效在跑 —— 渲染循环据此决定要不要继续画 */
    function busy() {
        return fx.moves.length > 0 || fx.ripples.length > 0 || fx.sparks.length > 0
            || fx.grazes.length > 0 || fx.trails.length > 0 || fx.drops.length > 0
            || !!fx.shake || problemCells.length > 0   // 红圈在脉动，也得一直重画
            || !!ghost || refusals.length > 0;         // 预判圈同样在脉动
    }

    /** 换局/重开局时把在演的东西清干净，免得残影飘在新盘面上 */
    function clearEffects() {
        fx.moves.length = 0; fx.ripples.length = 0; fx.sparks.length = 0;
        fx.grazes.length = 0; fx.trails.length = 0; fx.drops.length = 0; fx.shake = null;
        refusals.length = 0;
    }

    /* ==================== 音效：用 Web Audio 现场合成，不带音频文件 ==================== */

    let audioCtx = null;
    let soundOn = true;

    function setSound(on) { soundOn = !!on; if (!soundOn && audioCtx) audioCtx.suspend(); }
    function isSoundOn() { return soundOn; }

    function sound(kind, delayMs) {
        if (!soundOn) return;
        try {
            const AC = global.AudioContext || global.webkitAudioContext;
            if (!AC) return;
            if (!audioCtx) audioCtx = new AC();
            if (audioCtx.state === 'suspended') audioCtx.resume();
            const at = audioCtx.currentTime + (delayMs ? delayMs / 1000 * 0.55 : 0);
            if (kind === 'capture') {
                noise(at, 0.09, 780, 1.0, 0.30);
                tone(at, 132, 0.19, 0.26);
                tone(at + 0.012, 196, 0.13, 0.14);
            } else if (kind === 'refuse') {
                // 闷闷的两声往下走：一听就是"放不下去"，不像正常的落子声
                tone(at, 118, 0.11, 0.22);
                tone(at + 0.085, 82, 0.16, 0.20);
                noise(at, 0.05, 300, 0.8, 0.14);
            } else {
                noise(at, 0.05, 1600, 1.3, 0.20);
                tone(at, 232, 0.075, 0.13);
            }
        } catch (e) { /* 浏览器不给放就算了，不影响下棋 */ }
    }

    function noise(at, dur, freq, q, gain) {
        const rate = audioCtx.sampleRate;
        const len = Math.max(1, Math.round(rate * dur));
        const buf = audioCtx.createBuffer(1, len, rate);
        const data = buf.getChannelData(0);
        for (let i = 0; i < len; i++) data[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 2.4);
        const src = audioCtx.createBufferSource();
        src.buffer = buf;
        const bp = audioCtx.createBiquadFilter();
        bp.type = 'bandpass';
        bp.frequency.value = freq;
        bp.Q.value = q;
        const g = audioCtx.createGain();
        g.gain.value = gain;
        src.connect(bp); bp.connect(g); g.connect(audioCtx.destination);
        src.start(at);
    }

    function tone(at, freq, dur, gain) {
        const osc = audioCtx.createOscillator();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(freq, at);
        osc.frequency.exponentialRampToValueAtTime(Math.max(40, freq * 0.6), at + dur);
        const g = audioCtx.createGain();
        g.gain.setValueAtTime(0.0001, at);
        g.gain.exponentialRampToValueAtTime(gain, at + 0.006);
        g.gain.exponentialRampToValueAtTime(0.0001, at + dur);
        osc.connect(g); g.connect(audioCtx.destination);
        osc.start(at); osc.stop(at + dur + 0.03);
    }

    /* ==================== 渲染循环 ==================== */

    function now() { return performance.now(); }

    /**
     * 常驻 rAF 循环，但没脏没动画时直接跳过绘制，空转几乎不耗性能。
     * @param paint 收到时间戳后自己把整帧画出来
     */
    function createLoop(paint) {
        let dirty = true, running = true;
        function frame(ts) {
            if (!running) return;
            if (dirty || busy()) { dirty = false; paint(ts); }
            requestAnimationFrame(frame);
        }
        requestAnimationFrame(frame);
        return {
            invalidate: function () { dirty = true; },
            stop: function () { running = false; }
        };
    }

    /* ==================== 对外 ==================== */

    global.XQ = {
        COLS: COLS, ROWS: ROWS, W: W, H: H,
        PAD_L: PAD_L, PAD_T: PAD_T, CELL_W: CELL_W, CELL_H: CELL_H,
        R_PIECE: R_PIECE, GLYPH: GLYPH,
        isRed: isRed,
        center: centerOf, rect: rectOf, hit: hitTest,
        pieceSprite: pieceSprite, drawPiece: drawPiece,
        paintBoardLayer: paintBoardLayer,
        paintCellTint: paintCellTint,
        paintSelection: paintSelection,
        paintCheckPulse: paintCheckPulse,
        paintMoveTargets: paintMoveTargets,
        setProblemCells: setProblemCells,
        paintProblemMarks: paintProblemMarks,
        setGhost: setGhost,
        refuse: refuse,
        paintHints: paintHints,
        paintUnderPieces: paintUnderPieces,
        paintEffects: paintEffects,
        shakeOffset: shakeOffset,
        flyingCells: flyingCells,
        playMove: playMove,
        dropIn: dropIn,
        clearEffects: clearEffects,
        busy: busy,
        createLoop: createLoop,
        setSound: setSound, isSoundOn: isSoundOn, sound: sound,
        now: now
    };
})(window);
