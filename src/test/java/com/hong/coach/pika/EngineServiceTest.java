package com.hong.coach.pika;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class EngineServiceTest {

    @Autowired
    private EngineService engineService;


    @Test
    public void start() throws Exception {
        String h2e2 = engineService.bestMove("h2e2");
        System.out.println(h2e2);
    }
}
