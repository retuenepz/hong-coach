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
     * 用哪一家的模型：deepseek | dashscope | none。
     * <p>
     * deepseek 走本项目自研的流式客户端（见 {@link DeepSeekLlmClient}），
     * dashscope 走 Spring AI，none 表示不要大模型、教练只出本地提示。
     */
    private String provider = "deepseek";

    /**
     * 触发主动讲解的评分落差阈值（厘兵）。用户一步棋让局面变差超过该值就会主动开口。
     */
    private int triggerThreshold = 80;

    /**
     * 单次大模型调用的超时（毫秒）。
     * <p>
     * 流式下它管的是「两段输出之间最长停顿多久」——从订阅那一刻就开始计时，
     * 所以首字迟迟不来也算超时，用户不会对着转圈干等。超时则降级为本地提示。
     */
    private long timeoutMs = 40000;

    /**
     * 一条流式输出最多持续多久（毫秒），到点就收尾。
     * <p>
     * 与 {@link #timeoutMs} 分工不同：那个管「卡住不动」，这个管「一直在慢慢吐」。
     * 一般答问 200 字以内，十几秒就完了；这个上限只是兜底，正常不会碰到。
     */
    private long streamMaxMs = 180000;

    /**
     * 与模型服务建立连接的超时（毫秒）
     */
    private int httpConnectTimeoutMs = 10000;

    /**
     * 单次大模型 HTTP 请求的整体超时（毫秒），一直算到响应读完为止。
     *
     * 必须显式设置：Spring Boot 3.2 建 RestClient 时用的是各 HTTP 客户端自带的默认值，
     * classpath 上有 OkHttp（百炼 SDK 带进来的）时会被探测选中，而 OkHttp 默认读超时只有
     * 10 秒。大模型的「首字延迟」经常超过 10 秒，于是必然 SocketTimeoutException。
     *
     * 注意这条只作用于走 Spring AI 的那条路（百炼）；DeepSeek 自己发请求，用的是
     * {@link DeepSeek#firstByteTimeoutMs}。
     */
    private int httpReadTimeoutMs = 120000;

    /**
     * DeepSeek 开放平台的接入参数
     */
    private DeepSeek deepseek = new DeepSeek();

    @Data
    public static class DeepSeek {

        /** 只能从环境变量注入。没配时教练自动降级为本地提示，不影响下棋 */
        private String apiKey;

        private String baseUrl = "https://api.deepseek.com";

        /** 可选 deepseek-flash（快、便宜）或 deepseek-v4-pro（更强但慢得多） */
        private String model = "deepseek-flash";

        /**
         * 思考量。默认 {@code none} = <b>不思考</b>，直接影响首字延迟。
         * <p>
         * 引擎已经把棋算完了，模型只负责把结论翻译成人话，让它自己再推演一遍既慢又没有意义。
         * 实测（同一个提问，真实 key）：不传该字段首字 4.7~8.3 秒；{@code none} 时思考 token 归零、
         * 首字 0.4~0.5 秒，前端才真的能逐字往外跳。
         * <p>
         * 想让它重新推演（点评的细节会多些，但会重新卡住），把它设成
         * {@code low} / {@code high} / {@code max} 即可，也可以留空表示不传。
         */
        private String reasoningEffort = "none";

        /** 单次回复的 token 上限。讲解本身要求在 200 字以内，留足余量即可 */
        private int maxTokens = 800;

        /**
         * 到「响应头」为止的超时（毫秒）：包含 TLS 握手和服务端的排队/首字时间。
         * <p>
         * 用 JDK HttpClient 的 {@code HttpRequest.timeout}，语义正好是「到响应头」，
         * 不会误伤流式传输中途的正常停顿 —— 那种停顿由 {@link #timeoutMs} 管，
         * 而且能区分「首字没来」和「吐到一半卡住」，比一个笼统的读超时更好用。
         */
        private int firstByteTimeoutMs = 30000;
    }
}
