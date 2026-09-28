package com.hong.coach.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 没配 DashScope key 时，自动把整个 DashScope 自动配置关掉。
 * <p>
 * <b>为什么需要这个类</b>：spring-ai-alibaba 的自动配置在构造客户端时会对 api-key
 * 做一次「非空」断言（只断非空，不校验格式也不联网）。key 为空时，这个断言会以
 * {@code BeanCreationException} 的形式在<b>启动阶段</b>炸掉整个应用 —— 而这个项目的
 * 立场是「大模型可选」：没有 key 也应该能正常下棋、正常用引擎复盘。
 * <p>
 * <b>做法</b>：在环境准备阶段就把 {@code spring.ai.dashscope.enabled} 置为 {@code false}。
 * DashScope 的自动配置类都带 {@code @ConditionalOnDashScopeEnabled}
 * （即 {@code @ConditionalOnProperty("spring.ai.dashscope.enabled", matchIfMissing = true)}），
 * 于是它们会被整体跳过，一个 DashScope bean 都不会被创建，也就没有非空断言可炸。
 * {@link com.hong.coach.coach.CoachService} 用 {@code ObjectProvider<ChatModel>} 取模型，
 * 取不到就走本地降级提示，教练这条链路不会因此中断。
 * <p>
 * <b>为什么是 addLast</b>：加在属性源链的最末尾，优先级最低。用户在环境变量、
 * 配置文件或命令行里显式写的任何值都会盖过它 —— 也就是说配了真 key（或者手动
 * 写了 {@code spring.ai.dashscope.enabled=true}）的人，完全感知不到这个类的存在。
 */
public class DashScopeAutoConfigGuard implements EnvironmentPostProcessor, Ordered {

    static final String API_KEY_PROPERTY = "spring.ai.dashscope.api-key";
    static final String ENABLED_PROPERTY = "spring.ai.dashscope.enabled";
    private static final String SOURCE_NAME = "dashscope-auto-config-guard";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 配了 key：什么都不做，交回给 spring-ai-alibaba 自己判断
        if (StringUtils.hasText(environment.getProperty(API_KEY_PROPERTY))) {
            return;
        }
        // 用户自己对开关有主张：听用户的
        if (StringUtils.hasText(environment.getProperty(ENABLED_PROPERTY))) {
            return;
        }
        environment.getPropertySources().addLast(
                new MapPropertySource(SOURCE_NAME, Map.of(ENABLED_PROPERTY, "false")));
    }

    /**
     * 必须晚于 ConfigFileApplicationListener（它负责加载 application-*.yml），
     * 否则这里读不到配置文件里的 api-key，会把配了 key 的人也一并关掉。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
