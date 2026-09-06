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
        // 先付后做：支付成功（pay_time 写入）即具备评价资格，无需等待出餐结账；
        // 已结账（PAID）订单同样可评价；未支付或已取消的订单不可评价
        Order order = orderService.getById(review.getOrderId());
        boolean reviewable = order != null
                && (order.getStatus().equals(OrderStatusEnum.PAID.getCode())
                || (order.getPayTime() != null && !order.getStatus().equals(OrderStatusEnum.CANCELLED.getCode())));
        if (!reviewable) {
            throw new RuntimeException("只能对已支付的订单进行评价");
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
