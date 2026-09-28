package com.hong.coach.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教练对话记录。落库后刷新页面也能接着聊，不必从头再说一遍。
 */
@Data
@TableName("chess_chat_message")
public class ChessChatMessage {

    /** 消息角色 */
    public static final String ROLE_USER = "user";
    public static final String ROLE_COACH = "coach";

    @TableId(type = IdType.AUTO)
    @JsonIgnore
    private Long id;

    @JsonIgnore
    private String gameId;

    /** user / coach */
    private String role;

    /** 消息正文 */
    private String content;

    /** 气泡上方的小标签，可为空 */
    private String tag;

    @JsonIgnore
    private LocalDateTime createdAt;

    public ChessChatMessage() {
    }

    public ChessChatMessage(String role, String content, String tag) {
        this.role = role;
        this.content = content;
        this.tag = tag;
    }

    public static ChessChatMessage user(String content) {
        return new ChessChatMessage(ROLE_USER, content, null);
    }

    public static ChessChatMessage coach(String content, String tag) {
        return new ChessChatMessage(ROLE_COACH, content, tag);
    }
}
