package com.hong.coach.repository.mapper;

import com.hong.coach.repository.dto.ChessBoardDTO;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Date;

@SpringBootTest
@Slf4j
public class ChessBoardMapperTest {
    @Autowired
    private ChessBoardMapper chessBoardMapper;

    @Test
    public void selectById(){
        ChessBoardDTO chessBoardDTO = chessBoardMapper.selectById(1);
        log.info(chessBoardDTO.toString());
    }
    @Test
    public void insertTest(){
        ChessBoardDTO chessBoardDTO = new ChessBoardDTO();
        chessBoardDTO.setId(1L);
        chessBoardDTO.setCreateTime(new Date());
        chessBoardMapper.insert(chessBoardDTO);
    }
}
