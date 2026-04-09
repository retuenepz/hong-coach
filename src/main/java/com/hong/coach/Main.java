package com.hong.coach;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring AI Alibaba 应用启动类
 */
@SpringBootApplication
@EnableConfigurationProperties
@MapperScan("com.hong.coach.repository.mapper")
public class Main {
    public static void main(String[] args) {
        // 启动 Spring Boot 应用
        SpringApplication.run(Main.class, args);
    }
}