package com.smartkitchen.dto;

import com.smartkitchen.entity.StockLog;
import lombok.Data;

import java.util.List;

/**
 * 库存视图对象（含菜品信息 + 流水列表）
 */
@Data
public class StockVO {
    private Long dishId;
    private String dishName;
    private Integer dailyStock;
    private Integer alertThreshold;
    private List<StockLog> logs;
}
