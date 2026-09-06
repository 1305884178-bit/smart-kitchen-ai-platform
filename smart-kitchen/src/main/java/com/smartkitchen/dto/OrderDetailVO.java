package com.smartkitchen.dto;

import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.OrderDetail;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 订单详情展示视图对象（含可操作按钮列表）
 */
@Data
public class OrderDetailVO extends Order {
    private List<OrderDetail> details;
    private List<String> availableActions;
    /**
     * 待支付金额：仅统计未支付部分（父单未支付则含父单金额 + 未支付子订单金额），
     * 用于 C 端支付确认弹窗展示，避免补付加菜时把已支付金额重复计入
     */
    private BigDecimal payableAmount;
}
