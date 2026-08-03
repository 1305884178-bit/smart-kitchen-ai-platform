package com.smartkitchen.dto;

import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.OrderDetail;
import lombok.Data;

import java.util.List;

/**
 * 订单详情展示视图对象（含可操作按钮列表）
 */
@Data
public class OrderDetailVO extends Order {
    private List<OrderDetail> details;
    private List<String> availableActions;
}
