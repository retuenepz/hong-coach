package com.hong.coach.factory;

import com.hong.coach.game.Board;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 棋盘 工厂
 */
@Component
@Slf4j
public class BoardFactory{

    /**
     * 创建棋盘
     * @param id
     * @return
     */
    public Board createBoard(String id){
        Board methodRes = new Board(id);

        return methodRes;
    }

}
