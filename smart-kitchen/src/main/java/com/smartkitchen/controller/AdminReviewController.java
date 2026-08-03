package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.entity.Review;
import com.smartkitchen.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端评价控制器
 */
@RestController
@RequestMapping("/api/admin/review")
public class AdminReviewController {

    @Autowired
    private ReviewService reviewService;

    /**
     * 获取所有评价列表（支持按评分筛选）
     * @param score 评分筛选（可选，1-5）
     * @return 评价列表
     */
    @GetMapping("/list")
    public Result<List<Review>> listReviews(@RequestParam(required = false) Integer score) {
        return Result.success(reviewService.listByScore(score));
    }
}
