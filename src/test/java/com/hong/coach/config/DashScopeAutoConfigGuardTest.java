package com.hong.coach.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 覆盖 {@link DashScopeAutoConfigGuard} 的三条分支。
 * 这个类一旦不生效，表现是「没配 key 的应用直接起不来」，属于灾难级回归，
 * 所以三种情形都要钉住。
 */
class DashScopeAutoConfigGuardTest {

    private static final String KEY = DashScopeAutoConfigGuard.API_KEY_PROPERTY;
    private static final String ENABLED = DashScopeAutoConfigGuard.ENABLED_PROPERTY;

    private final DashScopeAutoConfigGuard guard = new DashScopeAutoConfigGuard();
    private final SpringApplication app = new SpringApplication();

    /** 造一个「配置已经加载完」的环境：值来自 Map，等价于 application.yml 里读到的结果 */
    private static ConfigurableEnvironment envWith(String key, String enabled) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> values = new LinkedHashMap<>();
        if (key != null) values.put(KEY, key);
        if (enabled != null) values.put(ENABLED, enabled);
        env.getPropertySources().addFirst(new MapPropertySource("test-config", values));
        return env;
    }

    @Test
    @DisplayName("没配 key：把 DashScope 关掉，避免它启动阶段炸掉应用")
    void disablesWhenKeyMissing() {
        ConfigurableEnvironment env = envWith("", null);

        guard.postProcessEnvironment(env, app);

        assertEquals("false", env.getProperty(ENABLED));
    }

    @Test
    @DisplayName("key 是空串或全空格，一律按没配处理")
    void treatsBlankKeyAsMissing() {
        for (String blank : new String[]{"", "   ", "\t"}) {
            ConfigurableEnvironment env = envWith(blank, null);
            guard.postProcessEnvironment(env, app);
            assertEquals("false", env.getProperty(ENABLED), "「" + blank + "」应被视为没配 key");
        }
    }

    @Test
    @DisplayName("配了 key：什么都不做，不碰开关")
    void leavesEverythingAloneWhenKeyPresent() {
        ConfigurableEnvironment env = envWith("sk-abcdef123456", null);

        guard.postProcessEnvironment(env, app);

        assertNull(env.getProperty(ENABLED), "配了 key 就不该再由这个类决定开关");
    }

    @Test
    @DisplayName("用户自己定了开关：听用户的，不覆盖")
    void respectsExplicitSwitch() {
        ConfigurableEnvironment env = envWith("", "true");

        guard.postProcessEnvironment(env, app);

        assertEquals("true", env.getProperty(ENABLED), "用户显式开了就别擅自关掉");
    }

    @Test
    @DisplayName("必须排在最后执行，否则读不到配置文件里的 key")
    void runsAfterConfigLoading() {
        assertEquals(Ordered.LOWEST_PRECEDENCE, guard.getOrder());
    }
}
