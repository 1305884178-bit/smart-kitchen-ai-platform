package com.smartkitchen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 菜品表实体类
 */
@Data
@TableName("pms_dish")
public class Dish {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long categoryId;
    private BigDecimal price;
    private String image;
    private Integer status;
    private Integer dailyStock;
    private Integer alertThreshold;
    private String ingredients;
    private String allergens;
    private Integer newProductInitialStock;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
