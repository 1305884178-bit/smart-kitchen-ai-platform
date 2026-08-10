package com.smartkitchen.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评价视图对象（管理端列表展示用）
 */
@Data
public class ReviewVO {
    private Long id;
    private Long orderId;
    private String orderNo;
    private Long userId;
    private Integer score;
    private String comment;
    private LocalDateTime createTime;
}
