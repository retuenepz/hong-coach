package com.hong.coach.web.dto;

import lombok.Data;

/**
 * 向 AI 教练提问
 */
@Data
public class ChatRequest {
    private String question;
}
