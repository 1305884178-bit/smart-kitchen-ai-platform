package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 顾客端菜品控制器
 */
@RestController
@RequestMapping("/api/dish")
public class DishController {

    /**
     * 获取菜品列表（包含分类、库存、价格信息）
     * @return 返回菜品列表数据
     */
    @GetMapping("/list")
    public Result<Object> list() {
        return Result.success(null);
    }
}
