package com.smartkitchen.dto;

import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.OrderDetail;
import lombok.Data;

import java.util.List;

/**
 * 订单展示视图对象
 */
@Data
public class OrderVO extends Order {
    private List<OrderDetail> details;
}
