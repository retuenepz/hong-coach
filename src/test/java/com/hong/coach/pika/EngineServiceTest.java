package com.hong.coach.pika;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;

@SpringBootTest
public class EngineServiceTest {

    @Autowired
    private EngineService engineService;

    /**
     * 炮二平五
     * @throws Exception
     */
    @Test
    public void test0() throws Exception {
        ArrayList<String> moves = new ArrayList<>();
        moves.add("h2e2");
        String h2e2 = engineService.bestMove(null,moves);
        System.out.println(h2e2);
    }

}
