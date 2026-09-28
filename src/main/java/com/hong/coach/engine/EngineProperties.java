package com.hong.coach.engine;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 象棋引擎相关配置（对应 application.yml 里的 engine 段）
 */
@ConfigurationProperties(prefix = "engine")
@Component
@Data
public class EngineProperties {

    /**
     * pika所在的路径地址。
     * 留空或指向不存在的目录时，会自动回退到 classpath 内置引擎（resources/engine）。
     */
    private String path;

    /**
     * 可执行文件名称
     */
    private String exeFileName = "pikafish-avx2.exe";

    /**
     * nnue算法文件名
     */
    private String nnueFileName = "pikafish.nnue";

    /**
     * AI 落子时的思考时间（毫秒）
     */
    private int playMovetime = 600;

    /**
     * 复盘分析（评分、主变）时的思考时间（毫秒）
     */
    private int analyzeMovetime = 900;

    /**
     * 分析时输出几路主变
     */
    private int multiPv = 3;

    /**
     * 引擎进程池大小，决定可同时服务的对局数
     */
    private int poolSize = 2;

    /**
     * 借用引擎的最长等待时间（毫秒）
     */
    private long borrowTimeoutMs = 15000;
}
