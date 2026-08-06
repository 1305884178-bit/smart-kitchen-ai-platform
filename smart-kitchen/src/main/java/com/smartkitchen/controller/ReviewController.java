package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.entity.Review;
import com.smartkitchen.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 顾客评价控制器
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    /**
     * 提交餐后评价
     */
    @PostMapping("/submit")
    public Result<Object> submitReview(@RequestBody Review review) {
        reviewService.submitReview(review);
        return Result.success();
    }
}
