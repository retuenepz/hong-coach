package com.hong.coach.web.dto;

import lombok.Data;

/**
 * 开局请求：用户选择执哪一方
 */
@Data
public class NewGameRequest {
    /** RED 或 BLACK，缺省为 RED */
    private String playerSide;
}
