package com.hong.coach.config;

import com.hong.coach.repository.GameRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 启动时执行 {@code db/schema.sql} 建表，并给历史库补上后加的字段。
 * <p>
 * 这里刻意不用 {@code spring.sql.init}：它在「连不上数据库」时会直接让应用启动失败，
 * 而本项目的立场是<b>数据库可选</b> —— 没有数据库也应该能正常下棋，只是刷新后丢对局。
 * 所以改成启动后自己跑一遍，任何失败都只记一条告警。
 * <p>
 * 脚本内容全是 {@code CREATE TABLE IF NOT EXISTS}，重复执行无副作用。
 */
@Slf4j
@Component
public class DatabaseSchemaInitializer implements ApplicationRunner {

    private final DataSource dataSource;
    private final GameRepository gameRepository;

    public DatabaseSchemaInitializer(DataSource dataSource, GameRepository gameRepository) {
        this.dataSource = dataSource;
        this.gameRepository = gameRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        ResourceDatabasePopulator populator =
                new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql"));
        populator.setContinueOnError(true);
        try {
            DatabasePopulatorUtils.execute(populator, dataSource);
            addMissingColumns();
            log.info("数据库表结构已就绪（chess_game / chess_move / chess_foul / chess_chat_message）");
        } catch (Exception e) {
            log.warn("建表失败，对局将只保存在内存里，重启后丢失：{}", e.getMessage());
            // 把「数据库现在不可用」这个事实同步给仓库，避免它对接口谎报可用
            gameRepository.reportUnavailable(e.getMessage());
        }
    }

    /**
     * 给已经建好的库补上后加的字段。
     * <p>
     * schema.sql 全是 {@code CREATE TABLE IF NOT EXISTS}，对已存在的表不会追加字段，
     * 而 MySQL 又不认 {@code ADD COLUMN IF NOT EXISTS}。所以这里查一遍元数据、缺什么补什么 ——
     * 新库与老库因此走的是同一段代码，不需要人肉执行迁移脚本。
     */
    private void addMissingColumns() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            ensureColumn(conn, "chess_game", "initial_fen",
                    "ALTER TABLE `chess_game` ADD COLUMN `initial_fen` VARCHAR(160) NULL "
                            + "COMMENT '开局时的局面 FEN：标准开局是满盘，用户摆棋时是那个残局' "
                            + "AFTER `winner`");
        }
    }

    private void ensureColumn(Connection conn, String table, String column, String ddl) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
            if (rs.next()) return;
        }
        try (Statement st = conn.createStatement()) {
            st.execute(ddl);
            log.info("为历史库补上字段 {}.{}", table, column);
        }
    }
}
