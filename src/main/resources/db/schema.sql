-- =====================================================================
--  hong-coach 数据库结构
--  库：chess_coach_db
--
--  设计说明
--  1. chess_game 存「一局的当前快照」，chess_move / chess_foul /
--     chess_chat_message 存明细。
--  2. 读档时不依赖快照里的盘面，而是先按 chess_game.initial_fen 摆出开局局面，
--     再把 chess_move 按 move_no 顺序重放 —— Board 的走子是确定性的，重放即可
--     精确还原任意时刻的盘面、回合与胜负。chess_game.fen 只作为自检依据。
--     initial_fen 让「用户自己摆残局再开打」的对局也能正确读档。
--  3. 写入策略是「整局覆盖」：先删子表再全量插入，天然幂等，
--     不需要维护增量同步状态。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `chess_game`
(
    `id`          VARCHAR(64)  NOT NULL COMMENT '对局标识，由前端生成并复用',
    `player_side` VARCHAR(8)   NOT NULL COMMENT '用户执子方：RED / BLACK',
    `status`      VARCHAR(16)  NOT NULL DEFAULT 'PLAYING' COMMENT 'PLAYING / END',
    `winner`      VARCHAR(8)            DEFAULT NULL COMMENT 'RED / BLACK，和棋为 NULL',
    `initial_fen` VARCHAR(160)          DEFAULT NULL COMMENT '开局时的局面 FEN：标准开局是满盘，用户摆棋时是那个残局',
    `fen`         VARCHAR(160) NOT NULL COMMENT '当前局面 FEN，读档后用于自检',
    `turn`        VARCHAR(8)   NOT NULL COMMENT '当前轮到哪一方走',
    `move_count`  INT          NOT NULL DEFAULT 0 COMMENT '已完成着法数',
    `created_at`  DATETIME     NOT NULL,
    `updated_at`  DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_updated_at` (`updated_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '对局主表（当前快照）';

CREATE TABLE IF NOT EXISTS `chess_move`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `game_id`    VARCHAR(64)  NOT NULL,
    `move_no`    INT          NOT NULL COMMENT '第几手，从 1 开始',
    `side`       VARCHAR(8)   NOT NULL COMMENT '走子方',
    `uci`        VARCHAR(8)   NOT NULL COMMENT 'UCI 着法',
    `chinese`    VARCHAR(32)  NOT NULL COMMENT '中文记谱',
    `captured`   TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否吃子',
    `ai`         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否电脑走出',
    `created_at` DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_game_move` (`game_id`, `move_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '着法明细（读档时按序重放）';

CREATE TABLE IF NOT EXISTS `chess_foul`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT,
    `game_id`    VARCHAR(64)  NOT NULL,
    `move_no`    INT          NOT NULL COMMENT '违规发生在第几手之前',
    `detail`     VARCHAR(255) NOT NULL COMMENT '违规描述',
    `created_at` DATETIME     NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_game` (`game_id`, `move_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '违规走子记录';

CREATE TABLE IF NOT EXISTS `chess_chat_message`
(
    `id`         BIGINT        NOT NULL AUTO_INCREMENT,
    `game_id`    VARCHAR(64)   NOT NULL,
    `role`       VARCHAR(16)   NOT NULL COMMENT 'user / coach',
    `content`    VARCHAR(2000) NOT NULL,
    `tag`        VARCHAR(64)            DEFAULT NULL COMMENT '气泡标签，如「漏着 · 建议 炮二平五」',
    `created_at` DATETIME      NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_game` (`game_id`, `id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '教练对话记录';
