package com.smartkitchen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI备菜预测记录表实体类
 */
@Data
@TableName("ai_prediction_record")
public class PredictionRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDate predictDate;
    private Long dishId;
    private Integer baseQuantity;
    private Integer aiSuggestQuantity;
    private Integer finalQuantity;
    private String reasoning;
    private BigDecimal confidence;
    private BigDecimal recentAvgScore;
    private Integer status;
    private Long confirmedBy;
    private LocalDateTime createTime;
}
