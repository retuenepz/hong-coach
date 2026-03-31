package com.hong.coach.pika.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * pika引擎相关配置
 */
@ConfigurationProperties(prefix = "engine")
@Component
@Data
public class EngineProperties {
    /**
     * pika所在的路径地址
     */
    private String path;
    /**
     * 可执行文件名称
     */
    private String exeFileName;
    /**
     * nnue算法文件名
     */
    private String nnueFileName;
    /**
     * 引擎思考的时常  毫秒数
     */
    private int movetime;

}
