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
        // 评价属于本次用餐的收尾动作：只有顾客确认结束用餐、订单关闭为 PAID 后才可评价。
        Order order = orderService.getById(review.getOrderId());
        boolean reviewable = order != null && order.getStatus().equals(OrderStatusEnum.PAID.getCode());
        if (!reviewable) {
            throw new RuntimeException("请结束用餐后再评价");
        }

        // 校验是否已经评价过
        Long exists = this.lambdaQuery()
                .eq(Review::getOrderId, review.getOrderId())
                .count();
        if (exists > 0) {
            throw new RuntimeException("该订单已经评价过，不能重复评价");
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

        // 批量查询关联订单，组装orderNo（无评价时直接返回空列表，避免 IN () 空参数报错）
        List<Long> orderIds = reviews.stream()
                .map(Review::getOrderId)
                .distinct()
                .collect(Collectors.toList());
        if (orderIds.isEmpty()) {
            return new ArrayList<>();
        }
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
