package com.smartkitchen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表实体类
 */
@Data
@TableName("oms_order")
public class Order {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String orderNo;
    private Long userId;
    private String seatNumber;
    private BigDecimal totalAmount;
    private Integer status;
    private String cancelReason;
    private LocalDateTime payTime;
    private String paymentTradeNo;
    private LocalDateTime completeTime;
    private Long operatorId;
    private String remark;
    private Long parentOrderId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
