package com.smartkitchen.controller;

import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.common.Result;
import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.Review;
import com.smartkitchen.service.OrderService;
import com.smartkitchen.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;

/**
 * 顾客评价控制器
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private OrderService orderService;

    /**
     * 提交餐后评价
     * @param review 评价实体
     * @param request HTTP请求
     * @return 返回评价提交结果
     */
    @PostMapping("/submit")
    public Result<Object> submitReview(@RequestBody Review review, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        review.setUserId(userId);

        // 校验订单状态是否为 PAID
        Order order = orderService.getById(review.getOrderId());
        if (order == null || !order.getStatus().equals(OrderStatusEnum.PAID.getCode())) {
            return Result.error(400, "只能对已结账的订单进行评价");
        }

        review.setCreateTime(LocalDateTime.now());
        reviewService.save(review);
        return Result.success();
    }
}
