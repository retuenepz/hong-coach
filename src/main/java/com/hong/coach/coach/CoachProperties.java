package com.hong.coach.coach;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 教练相关配置
 */
@ConfigurationProperties(prefix = "coach")
@Component
@Data
public class CoachProperties {

    /**
     * 是否启用大模型讲解（关闭后仅做引擎分析，不产生文字点评）
     */
    private boolean enabled = true;

    /**
     * 触发主动讲解的评分落差阈值（厘兵）。用户一步棋让局面变差超过该值就会主动开口。
     */
    private int triggerThreshold = 80;

    /**
     * 单次大模型调用的超时（毫秒），超时则降级为本地生成的点评
     */
    private long timeoutMs = 40000;
}
