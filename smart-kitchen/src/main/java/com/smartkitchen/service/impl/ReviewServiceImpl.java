package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.config.UserContext;
import com.smartkitchen.dto.ReviewVO;
import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.Review;
import com.smartkitchen.mapper.ReviewMapper;
import com.smartkitchen.service.OrderService;
import com.smartkitchen.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    public List<ReviewVO> listByScore(Integer score) {
        QueryWrapper<Review> wrapper = new QueryWrapper<>();
        if (score != null) {
            wrapper.eq("score", score);
        }
        wrapper.orderByDesc("create_time");
        List<Review> reviews = this.list(wrapper);

        // 批量查询关联订单，组装orderNo
        List<Long> orderIds = reviews.stream()
                .map(Review::getOrderId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> orderNoMap = orderService.listByIds(orderIds).stream()
                .collect(Collectors.toMap(Order::getId, Order::getOrderNo));

        List<ReviewVO> result = new ArrayList<>();
        for (Review r : reviews) {
            ReviewVO vo = new ReviewVO();
            vo.setId(r.getId());
            vo.setOrderId(r.getOrderId());
            vo.setOrderNo(orderNoMap.getOrDefault(r.getOrderId(), ""));
            vo.setUserId(r.getUserId());
            vo.setScore(r.getScore());
            vo.setComment(r.getComment());
            vo.setCreateTime(r.getCreateTime());
            result.add(vo);
        }
        return result;
    }
}
