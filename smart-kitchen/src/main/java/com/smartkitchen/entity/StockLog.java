package com.smartkitchen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存变更流水表实体类
 */
@Data
@TableName("inv_stock_log")
public class StockLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dishId;
    private String changeType;
    private Integer changeQty;
    private Integer beforeQty;
    private Integer afterQty;
    private String orderNo;
    private LocalDateTime createTime;
}
