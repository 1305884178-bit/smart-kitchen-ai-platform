package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客评价控制器
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    /**
     * 提交餐后评价
     * @return 返回评价提交结果
     */
    @PostMapping("/submit")
    public Result<Object> submitReview() {
        return Result.success(null);
    }
}
