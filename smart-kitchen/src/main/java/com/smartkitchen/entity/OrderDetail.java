package com.smartkitchen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细表实体类
 */
@Data
@TableName("oms_order_detail")
public class OrderDetail {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long orderId;
    private Long dishId;
    private String dishName;
    private Integer quantity;
    private BigDecimal price;
    private Integer isAdded;
    private LocalDateTime createTime;
}
