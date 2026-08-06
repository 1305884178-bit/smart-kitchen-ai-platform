package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.config.UserContext;
import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.Review;
import com.smartkitchen.mapper.ReviewMapper;
import com.smartkitchen.service.OrderService;
import com.smartkitchen.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价服务实现类
 */
@Service
public class ReviewServiceImpl extends ServiceImpl<ReviewMapper, Review> implements ReviewService {

    @Autowired
    private OrderService orderService;

    @Override
    public void submitReview(Review review) {
        // 校验订单状态是否为 PAID
        Order order = orderService.getById(review.getOrderId());
        if (order == null || !order.getStatus().equals(OrderStatusEnum.PAID.getCode())) {
            throw new RuntimeException("只能对已结账的订单进行评价");
        }

        review.setUserId(UserContext.getUserId());
        review.setCreateTime(LocalDateTime.now());
        this.save(review);
    }

    @Override
    public List<Review> listByScore(Integer score) {
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        if (score != null) {
            wrapper.eq("score", score);
        }
        wrapper.orderByDesc("create_time");
        return this.list(wrapper);
    }
}
