package com.smartkitchen.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜品详情视图对象（含分类名与已有评价）
 */
@Data
public class DishDetailVO {
    private Long id;
    private String name;
    private Long categoryId;
    private String categoryName;
    private BigDecimal price;
    private String image;
    private Integer status;
    private Integer dailyStock;
    private Integer alertThreshold;
    private String ingredients;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private List<ReviewItem> reviews;

    /**
     * 评价简项（菜品详情页展示用）
     */
    @Data
    public static class ReviewItem {
        private Integer score;
        private String comment;
        private LocalDateTime createTime;
    }
}
